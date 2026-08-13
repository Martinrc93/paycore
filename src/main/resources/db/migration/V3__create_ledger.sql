CREATE TABLE ledger_accounts (
    id UUID PRIMARY KEY,
    account_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    name VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_ledger_account_type CHECK (account_type IN (
        'ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE'
    )),
    CONSTRAINT ck_ledger_account_status CHECK (status IN ('OPEN', 'BLOCKED', 'CLOSED'))
);

CREATE TABLE ledger_transactions (
    id UUID PRIMARY KEY,
    posted_at TIMESTAMPTZ NOT NULL,
    value_date DATE NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    operation_reference VARCHAR(128) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    reversal_of UUID REFERENCES ledger_transactions(id),
    correction_of UUID REFERENCES ledger_transactions(id),
    CONSTRAINT ck_ledger_transaction_currency CHECK (currency IN ('ARS', 'USD', 'EUR', 'JPY')),
    CONSTRAINT ck_ledger_transaction_correction CHECK (NOT (reversal_of IS NOT NULL AND correction_of IS NOT NULL)),
    UNIQUE (id, currency)
);

CREATE TABLE ledger_transaction_lines (
    transaction_id UUID NOT NULL,
    line_sequence INTEGER NOT NULL,
    account_id UUID NOT NULL REFERENCES ledger_accounts(id),
    direction VARCHAR(8) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    CONSTRAINT pk_ledger_transaction_lines PRIMARY KEY (transaction_id, line_sequence),
    CONSTRAINT fk_ledger_line_transaction_currency FOREIGN KEY (transaction_id, currency)
        REFERENCES ledger_transactions(id, currency) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_ledger_line_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_line_sequence CHECK (line_sequence > 0),
    CONSTRAINT ck_ledger_line_amount CHECK (amount > 0),
    CONSTRAINT ck_ledger_line_currency CHECK (currency IN ('ARS', 'USD', 'EUR', 'JPY')),
    CONSTRAINT ck_ledger_line_jpy_scale CHECK (currency <> 'JPY' OR amount = trunc(amount))
);

CREATE TABLE ledger_posting_idempotency (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    request_fingerprint VARCHAR(64) NOT NULL,
    transaction_id UUID UNIQUE REFERENCES ledger_transactions(id),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_ledger_lines_account_order
    ON ledger_transaction_lines (account_id, transaction_id, line_sequence);
CREATE INDEX ix_ledger_transactions_posted_order
    ON ledger_transactions (posted_at, id);

CREATE FUNCTION reject_ledger_history_mutation() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'confirmed ledger history is append-only';
END;
$$;

CREATE TRIGGER trg_ledger_transactions_append_only
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_history_mutation();

CREATE TRIGGER trg_ledger_transaction_lines_append_only
    BEFORE UPDATE OR DELETE ON ledger_transaction_lines
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_history_mutation();

CREATE FUNCTION validate_ledger_line_account() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM ledger_accounts
         WHERE id = NEW.account_id AND status = 'OPEN'
    ) THEN
        RAISE EXCEPTION 'ledger line account must be open';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ledger_line_open_account
    BEFORE INSERT ON ledger_transaction_lines
    FOR EACH ROW EXECUTE FUNCTION validate_ledger_line_account();

CREATE FUNCTION assert_ledger_transaction_balance(target_transaction_id UUID) RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    debit_count INTEGER;
    credit_count INTEGER;
    currency_count INTEGER;
    debit_total NUMERIC;
    credit_total NUMERIC;
BEGIN
    SELECT COUNT(*) FILTER (WHERE direction = 'DEBIT'),
           COUNT(*) FILTER (WHERE direction = 'CREDIT'),
           COUNT(DISTINCT currency),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO debit_count, credit_count, currency_count, debit_total, credit_total
      FROM ledger_transaction_lines
     WHERE transaction_id = target_transaction_id;

    IF debit_count = 0 OR credit_count = 0 OR currency_count <> 1 OR debit_total <> credit_total THEN
        RAISE EXCEPTION 'ledger transaction must contain balanced lines in one currency';
    END IF;
END;
$$;

CREATE FUNCTION validate_ledger_transaction_lines_balance() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM assert_ledger_transaction_balance(NEW.transaction_id);
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_ledger_transaction_header_balance() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM assert_ledger_transaction_balance(NEW.id);
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_ledger_transaction_balanced
    AFTER INSERT ON ledger_transaction_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_ledger_transaction_lines_balance();

CREATE CONSTRAINT TRIGGER trg_ledger_transaction_insert_balanced
    AFTER INSERT ON ledger_transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_ledger_transaction_header_balance();

CREATE FUNCTION require_ledger_idempotency_claim() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM ledger_posting_idempotency
         WHERE idempotency_key = NEW.idempotency_key
    ) THEN
        RAISE EXCEPTION 'ledger transaction requires an idempotency claim';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ledger_transaction_idempotency_claim
    BEFORE INSERT ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION require_ledger_idempotency_claim();
