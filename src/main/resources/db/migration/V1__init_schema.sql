-- ============================================================
-- V1: Initial database schema
-- Concurrent Payment & Ledger Service
-- ============================================================


-- ============================================================
-- 1. UUID SUPPORT
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;


-- ============================================================
-- 2. ACCOUNTS
-- Stores the current balance of each account.
-- ============================================================

CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id VARCHAR(64) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    balance NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_accounts_balance_non_negative
        CHECK (balance >= 0)
);

CREATE INDEX idx_accounts_user_id
    ON accounts(user_id);


-- ============================================================
-- 3. TRANSACTIONS
-- Stores the high-level transfer between two accounts.
-- ============================================================

CREATE TYPE transaction_status AS ENUM (
    'PENDING',
    'SUCCESS',
    'FAILED'
);

CREATE TABLE transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_account_id UUID NOT NULL,
    target_account_id UUID NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    status transaction_status NOT NULL DEFAULT 'PENDING',
    failure_reason VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_transactions_source_account
        FOREIGN KEY (source_account_id)
        REFERENCES accounts(id),

    CONSTRAINT fk_transactions_target_account
        FOREIGN KEY (target_account_id)
        REFERENCES accounts(id),

    CONSTRAINT chk_transactions_positive_amount
        CHECK (amount > 0),

    CONSTRAINT chk_transactions_different_accounts
        CHECK (source_account_id <> target_account_id)
);

CREATE INDEX idx_transactions_source
    ON transactions(source_account_id);

CREATE INDEX idx_transactions_target
    ON transactions(target_account_id);


-- ============================================================
-- 4. LEDGER ENTRIES
-- Immutable financial record.
--
-- Every successful transfer creates exactly two entries:
--   1. DEBIT  from source account
--   2. CREDIT to target account
-- ============================================================

CREATE TYPE entry_type AS ENUM (
    'DEBIT',
    'CREDIT'
);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL,
    account_id UUID NOT NULL,
    type entry_type NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    balance_after NUMERIC(19, 4) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_ledger_transaction
        FOREIGN KEY (transaction_id)
        REFERENCES transactions(id),

    CONSTRAINT fk_ledger_account
        FOREIGN KEY (account_id)
        REFERENCES accounts(id),

    CONSTRAINT chk_ledger_positive_amount
        CHECK (amount > 0),

    CONSTRAINT chk_ledger_balance_non_negative
        CHECK (balance_after >= 0)
);

CREATE INDEX idx_ledger_account_created
    ON ledger_entries(account_id, created_at DESC);

CREATE INDEX idx_ledger_transaction
    ON ledger_entries(transaction_id);


-- ============================================================
-- 5. IDEMPOTENCY KEYS
-- Prevents the same payment request from being processed twice.
-- ============================================================

CREATE TYPE idempotency_status AS ENUM (
    'PROCESSING',
    'COMPLETED',
    'FAILED'
);

CREATE TABLE idempotency_keys (
    key VARCHAR(128) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    status idempotency_status NOT NULL DEFAULT 'PROCESSING',
    response_code INTEGER,
    response_body JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_idempotency_expiry
    ON idempotency_keys(expires_at);