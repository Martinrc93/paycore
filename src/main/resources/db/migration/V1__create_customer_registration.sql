CREATE TABLE customers (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    customer_type VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_customers_type CHECK (customer_type IN ('INDIVIDUAL', 'BUSINESS')),
    CONSTRAINT ck_customers_status CHECK (status IN (
        'PROVISIONING', 'ACTIVE', 'PROVISIONING_FAILED', 'SUSPENDED', 'BLOCKED'
    ))
);

CREATE TABLE external_identities (
    issuer VARCHAR(512) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    customer_id UUID NOT NULL REFERENCES customers(id),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (issuer, subject),
    UNIQUE (customer_id, issuer)
);

CREATE TABLE registration_operations (
    id UUID PRIMARY KEY,
    key_reference VARCHAR(80) NOT NULL,
    key_digest_version INTEGER NOT NULL,
    key_digest VARCHAR(64) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    customer_id UUID REFERENCES customers(id),
    state VARCHAR(32) NOT NULL,
    external_subject VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ,
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    fencing_version BIGINT NOT NULL DEFAULT 0,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    failure_code VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_registration_state CHECK (state IN (
        'PENDING_IDENTITY', 'IDENTITY_LINKED', 'COMPLETED',
        'DUPLICATE_SUPPRESSED', 'RECONCILIATION_REQUIRED'
    ))
);

CREATE INDEX ix_registration_due
    ON registration_operations (state, next_attempt_at, lease_until)
    WHERE state IN ('PENDING_IDENTITY', 'IDENTITY_LINKED');

CREATE INDEX ix_registration_customer ON registration_operations (customer_id);
CREATE INDEX ix_registration_expiry ON registration_operations (expires_at);
CREATE INDEX ix_registration_key_reference ON registration_operations (key_reference, created_at DESC);

CREATE TABLE registration_rate_limits (
    bucket_key CHAR(64) NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (bucket_key, window_start)
);

CREATE INDEX ix_registration_rate_limit_expiry ON registration_rate_limits (expires_at);
