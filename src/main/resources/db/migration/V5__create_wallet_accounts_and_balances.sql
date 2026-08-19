DO $$
BEGIN
    IF EXISTS (
        SELECT account_id
          FROM ledger_transaction_lines
         GROUP BY account_id
        HAVING COUNT(DISTINCT currency) > 1
    ) THEN
        RAISE EXCEPTION 'multiple historical currencies for a ledger account';
    END IF;
END;
$$;

ALTER TABLE ledger_accounts
    ADD COLUMN currency VARCHAR(3),
    ADD COLUMN balance_policy VARCHAR(16);

UPDATE ledger_accounts a
   SET currency = COALESCE((
           SELECT MIN(l.currency)
             FROM ledger_transaction_lines l
            WHERE l.account_id = a.id
       ), 'USD'),
       balance_policy = 'ALLOW_NEGATIVE';

ALTER TABLE ledger_accounts
    ALTER COLUMN currency SET NOT NULL,
    ALTER COLUMN balance_policy SET NOT NULL;

ALTER TABLE ledger_accounts
    ADD CONSTRAINT ck_ledger_account_currency CHECK (currency IN ('ARS', 'USD', 'EUR', 'JPY')),
    ADD CONSTRAINT ck_ledger_account_balance_policy CHECK (balance_policy IN ('NON_NEGATIVE', 'ALLOW_NEGATIVE'));

CREATE TABLE ledger_account_balances (
    account_id UUID PRIMARY KEY REFERENCES ledger_accounts(id),
    cumulative_debits NUMERIC(19, 2) NOT NULL DEFAULT 0,
    cumulative_credits NUMERIC(19, 2) NOT NULL DEFAULT 0,
    consistency_status VARCHAR(16) NOT NULL DEFAULT 'CONSISTENT',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ledger_account_balance_debits CHECK (cumulative_debits >= 0),
    CONSTRAINT ck_ledger_account_balance_credits CHECK (cumulative_credits >= 0),
    CONSTRAINT ck_ledger_account_balance_consistency
        CHECK (consistency_status IN ('CONSISTENT', 'INCONSISTENT'))
 );

CREATE FUNCTION validate_ledger_account_balance_natural() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    account_type_value VARCHAR(16);
    balance_policy_value VARCHAR(16);
BEGIN
    SELECT account_type, balance_policy
      INTO account_type_value, balance_policy_value
      FROM ledger_accounts
     WHERE id = NEW.account_id;

    IF balance_policy_value = 'NON_NEGATIVE'
       AND ((account_type_value IN ('ASSET', 'EXPENSE')
             AND NEW.cumulative_debits - NEW.cumulative_credits < 0)
            OR (account_type_value IN ('LIABILITY', 'EQUITY', 'REVENUE')
                AND NEW.cumulative_credits - NEW.cumulative_debits < 0)) THEN
        RAISE EXCEPTION 'non-negative ledger account balance cannot be negative';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ledger_account_balance_natural
    BEFORE INSERT OR UPDATE ON ledger_account_balances
    FOR EACH ROW EXECUTE FUNCTION validate_ledger_account_balance_natural();

CREATE FUNCTION validate_ledger_account_policy_natural() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.balance_policy = 'NON_NEGATIVE'
       AND EXISTS (
           SELECT 1
             FROM ledger_account_balances b
            WHERE b.account_id = NEW.id
              AND ((NEW.account_type IN ('ASSET', 'EXPENSE')
                    AND b.cumulative_debits - b.cumulative_credits < 0)
                   OR (NEW.account_type IN ('LIABILITY', 'EQUITY', 'REVENUE')
                       AND b.cumulative_credits - b.cumulative_debits < 0))
       ) THEN
        RAISE EXCEPTION 'non-negative ledger account balance cannot be negative';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ledger_account_policy_natural
    BEFORE UPDATE OF account_type, balance_policy ON ledger_accounts
    FOR EACH ROW EXECUTE FUNCTION validate_ledger_account_policy_natural();

INSERT INTO ledger_account_balances (
    account_id, cumulative_debits, cumulative_credits, consistency_status, updated_at
)
SELECT a.id,
       COALESCE(SUM(l.amount) FILTER (WHERE l.direction = 'DEBIT'), 0),
       COALESCE(SUM(l.amount) FILTER (WHERE l.direction = 'CREDIT'), 0),
       'CONSISTENT',
       CURRENT_TIMESTAMP
  FROM ledger_accounts a
  LEFT JOIN ledger_transaction_lines l ON l.account_id = a.id
 GROUP BY a.id;

DROP TRIGGER IF EXISTS trg_ledger_line_open_account ON ledger_transaction_lines;
DROP FUNCTION IF EXISTS validate_ledger_line_account();

CREATE FUNCTION validate_ledger_line_account() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    account_status VARCHAR(16);
    account_currency VARCHAR(3);
BEGIN
    SELECT status, currency
      INTO account_status, account_currency
      FROM ledger_accounts
     WHERE id = NEW.account_id;

    IF NOT FOUND OR account_status <> 'OPEN' THEN
        RAISE EXCEPTION 'ledger line account must be open';
    END IF;

    IF account_currency IS DISTINCT FROM NEW.currency THEN
        RAISE EXCEPTION 'ledger line currency must match account currency';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ledger_line_open_account
    BEFORE INSERT ON ledger_transaction_lines
    FOR EACH ROW EXECUTE FUNCTION validate_ledger_line_account();

CREATE TABLE wallets (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL UNIQUE REFERENCES customers(id),
    currency VARCHAR(3) NOT NULL,
    available_account_id UUID NOT NULL UNIQUE REFERENCES ledger_accounts(id),
    reserved_account_id UUID NOT NULL UNIQUE REFERENCES ledger_accounts(id),
    status VARCHAR(16) NOT NULL,
    pre_block_status VARCHAR(16),
    activated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_wallet_currency CHECK (currency = 'USD'),
    CONSTRAINT ck_wallet_status CHECK (status IN ('UNFUNDED', 'ACTIVE', 'BLOCKED', 'CLOSED')),
    CONSTRAINT ck_wallet_active_requires_activation CHECK (
        status <> 'ACTIVE' OR activated_at IS NOT NULL
    ),
    CONSTRAINT ck_wallet_pre_block_status CHECK (pre_block_status IS NULL OR pre_block_status IN ('UNFUNDED', 'ACTIVE')),
    CONSTRAINT ck_wallet_block_status CHECK (
        (status = 'BLOCKED' AND pre_block_status IS NOT NULL)
        OR (status <> 'BLOCKED' AND pre_block_status IS NULL)
    ),
    CONSTRAINT ck_wallet_distinct_accounts CHECK (available_account_id <> reserved_account_id),
    CONSTRAINT ck_wallet_version CHECK (version >= 0)
);

CREATE INDEX ix_wallet_customer ON wallets(customer_id);

CREATE FUNCTION validate_attached_wallet_account() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM wallets w
         WHERE (w.available_account_id = NEW.id OR w.reserved_account_id = NEW.id)
            AND (NEW.account_type <> 'LIABILITY'
                OR NEW.currency <> 'USD'
                OR NEW.balance_policy <> 'NON_NEGATIVE')
    ) THEN
        RAISE EXCEPTION 'wallet accounts must remain USD non-negative liability accounts';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_wallet_account_links() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    matching_accounts INTEGER;
BEGIN
    IF NEW.available_account_id = NEW.reserved_account_id THEN
        RAISE EXCEPTION 'wallet account references must be distinct';
    END IF;

    SELECT COUNT(*)
      INTO matching_accounts
      FROM ledger_accounts a
      JOIN ledger_account_balances b ON b.account_id = a.id
     WHERE a.id IN (NEW.available_account_id, NEW.reserved_account_id)
       AND a.account_type = 'LIABILITY'
       AND a.currency = 'USD'
       AND a.balance_policy = 'NON_NEGATIVE'
       AND (NEW.status IN ('BLOCKED', 'CLOSED')
            OR b.consistency_status = 'CONSISTENT');

    IF matching_accounts <> 2 THEN
        RAISE EXCEPTION 'wallet accounts must match lifecycle status and remain USD non-negative liability accounts';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_wallet_account_links
    BEFORE INSERT OR UPDATE ON wallets
    FOR EACH ROW EXECUTE FUNCTION validate_wallet_account_links();

CREATE TRIGGER trg_attached_wallet_account_validation
    BEFORE INSERT OR UPDATE ON ledger_accounts
    FOR EACH ROW EXECUTE FUNCTION validate_attached_wallet_account();

CREATE FUNCTION validate_wallet_account_lifecycle() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    wallet_id UUID;
    wallet_status VARCHAR(16);
    available_account_id UUID;
    reserved_account_id UUID;
    expected_account_status VARCHAR(16);
    matching_accounts INTEGER;
BEGIN
    IF TG_TABLE_NAME = 'wallets' THEN
        wallet_id := NEW.id;
    ELSE
        SELECT w.id
          INTO wallet_id
          FROM wallets w
         WHERE w.available_account_id = NEW.id
            OR w.reserved_account_id = NEW.id;
        IF NOT FOUND THEN
            RETURN NEW;
        END IF;
    END IF;

    SELECT w.status, w.available_account_id, w.reserved_account_id
      INTO wallet_status, available_account_id, reserved_account_id
      FROM wallets w
     WHERE w.id = wallet_id;

    expected_account_status := CASE
        WHEN wallet_status IN ('UNFUNDED', 'ACTIVE') THEN 'OPEN'
        WHEN wallet_status = 'BLOCKED' THEN 'BLOCKED'
        WHEN wallet_status = 'CLOSED' THEN 'CLOSED'
    END;

    SELECT COUNT(*)
      INTO matching_accounts
      FROM ledger_accounts a
     WHERE a.id IN (available_account_id, reserved_account_id)
       AND a.status = expected_account_status;

    IF matching_accounts <> 2 THEN
        RAISE EXCEPTION 'wallet account status must match wallet lifecycle';
    END IF;

    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_wallet_account_lifecycle_wallet
    AFTER INSERT OR UPDATE OF status, available_account_id, reserved_account_id ON wallets
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_wallet_account_lifecycle();

CREATE CONSTRAINT TRIGGER trg_wallet_account_lifecycle_account
    AFTER UPDATE OF status ON ledger_accounts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_wallet_account_lifecycle();

DO $$
DECLARE
    active_customer_id UUID;
    wallet_id UUID;
    available_account_id UUID;
    reserved_account_id UUID;
    wallet_count INTEGER;
BEGIN
    FOR active_customer_id IN
        SELECT c.id FROM customers c WHERE c.status = 'ACTIVE'
    LOOP
        SELECT COUNT(*)
          INTO wallet_count
          FROM wallets w
         WHERE w.customer_id = active_customer_id;

        IF wallet_count > 1 THEN
            RAISE EXCEPTION 'customer owns multiple wallets';
        ELSIF wallet_count = 1 THEN
            IF NOT EXISTS (
                SELECT 1
                  FROM wallets w
                  JOIN ledger_accounts available_account
                    ON available_account.id = w.available_account_id
                  JOIN ledger_accounts reserved_account
                    ON reserved_account.id = w.reserved_account_id
                  JOIN ledger_account_balances available_balance
                    ON available_balance.account_id = w.available_account_id
                  JOIN ledger_account_balances reserved_balance
                    ON reserved_balance.account_id = w.reserved_account_id
                 WHERE w.customer_id = active_customer_id
                   AND w.currency = 'USD'
                   AND w.status IN ('UNFUNDED', 'ACTIVE')
                   AND available_account.account_type = 'LIABILITY'
                   AND available_account.status = 'OPEN'
                   AND available_account.currency = 'USD'
                   AND available_account.balance_policy = 'NON_NEGATIVE'
                   AND available_account.id = available_balance.account_id
                   AND available_balance.account_id = w.available_account_id
                   AND available_balance.consistency_status = 'CONSISTENT'
                   AND reserved_account.id = reserved_balance.account_id
                   AND reserved_balance.account_id = w.reserved_account_id
                   AND reserved_account.account_type = 'LIABILITY'
                   AND reserved_account.status = 'OPEN'
                   AND reserved_account.currency = 'USD'
                   AND reserved_account.balance_policy = 'NON_NEGATIVE'
                   AND reserved_balance.consistency_status = 'CONSISTENT'
             ) THEN
                RAISE EXCEPTION 'active customer wallet is incomplete';
            END IF;
            CONTINUE;
        END IF;

        wallet_id := gen_random_uuid();
        available_account_id := gen_random_uuid();
        reserved_account_id := gen_random_uuid();

        INSERT INTO ledger_accounts (
            id, account_type, status, name, currency, balance_policy, created_at
        ) VALUES
            (available_account_id, 'LIABILITY', 'OPEN',
             'wallet:' || active_customer_id || ':available', 'USD', 'NON_NEGATIVE', CURRENT_TIMESTAMP),
            (reserved_account_id, 'LIABILITY', 'OPEN',
             'wallet:' || active_customer_id || ':reserved', 'USD', 'NON_NEGATIVE', CURRENT_TIMESTAMP);

        INSERT INTO ledger_account_balances (account_id, updated_at)
        VALUES (available_account_id, CURRENT_TIMESTAMP),
               (reserved_account_id, CURRENT_TIMESTAMP);

        INSERT INTO wallets (
            id, customer_id, currency, available_account_id, reserved_account_id,
            status, created_at, updated_at, version
        ) VALUES (
            wallet_id, active_customer_id, 'USD', available_account_id, reserved_account_id,
            'UNFUNDED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
        );
    END LOOP;
END;
$$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM customers c
         WHERE c.status = 'ACTIVE'
           AND NOT EXISTS (
               SELECT 1
                 FROM wallets w
                 JOIN ledger_accounts available_account
                   ON available_account.id = w.available_account_id
                  JOIN ledger_accounts reserved_account
                    ON reserved_account.id = w.reserved_account_id
                  JOIN ledger_account_balances available_balance
                    ON available_balance.account_id = w.available_account_id
                  JOIN ledger_account_balances reserved_balance
                    ON reserved_balance.account_id = w.reserved_account_id
                 WHERE w.customer_id = c.id
                  AND w.currency = 'USD'
                  AND w.status IN ('UNFUNDED', 'ACTIVE')
                  AND available_account.account_type = 'LIABILITY'
                   AND available_account.status = 'OPEN'
                   AND available_account.currency = 'USD'
                   AND available_account.balance_policy = 'NON_NEGATIVE'
                   AND available_balance.consistency_status = 'CONSISTENT'
                   AND reserved_account.account_type = 'LIABILITY'
                   AND reserved_account.status = 'OPEN'
                   AND reserved_account.currency = 'USD'
                   AND reserved_account.balance_policy = 'NON_NEGATIVE'
                   AND reserved_balance.consistency_status = 'CONSISTENT'
           )
    ) THEN
        RAISE EXCEPTION 'every active customer must have a complete wallet';
    END IF;
END;
$$;
