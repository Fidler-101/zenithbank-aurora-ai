-- ============================================================
-- ZenithBank | Banking Service — Database Schema
-- Database : zenithbank_banking
-- Author   : SP Nkosi
-- ============================================================

DROP TABLE IF EXISTS async_transaction_jobs CASCADE;
DROP TABLE IF EXISTS transactions            CASCADE;
DROP TABLE IF EXISTS accounts               CASCADE;

-- ============================================================
-- 1. ACCOUNTS
--    One customer may have multiple accounts (Cheque, Savings,
--    Business). customerId is a foreign reference — it is owned
--    by customer-service, not this DB.
-- ============================================================
CREATE TABLE accounts (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id         BIGINT       NOT NULL,
    account_number      VARCHAR(12)  NOT NULL UNIQUE,
    account_type        VARCHAR(20)  NOT NULL
                            CHECK (account_type IN ('CHEQUE','SAVINGS','BUSINESS','FIXED_DEPOSIT')),
    account_name        VARCHAR(100) NOT NULL,
    balance             NUMERIC(15,2) NOT NULL DEFAULT 0.00,
    available_balance   NUMERIC(15,2) NOT NULL DEFAULT 0.00,
    currency            VARCHAR(3)   NOT NULL DEFAULT 'ZAR',
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                            CHECK (status IN ('ACTIVE','FROZEN','CLOSED','DORMANT')),
    interest_rate       NUMERIC(5,4) DEFAULT 0.0000,
    overdraft_limit     NUMERIC(15,2) NOT NULL DEFAULT 0.00,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ============================================================
-- 2. TRANSACTIONS
--    Immutable ledger — rows are never updated or deleted.
--    Balance is computed from transactions, not stored here.
-- ============================================================
CREATE TABLE transactions (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id          UUID         NOT NULL REFERENCES accounts(id),
    reference_number    VARCHAR(20)  NOT NULL UNIQUE,
    type                VARCHAR(20)  NOT NULL
                            CHECK (type IN ('CREDIT','DEBIT','TRANSFER_IN','TRANSFER_OUT',
                                            'FEE','INTEREST','REVERSAL')),
    amount              NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    balance_after       NUMERIC(15,2) NOT NULL,
    description         VARCHAR(255) NOT NULL,
    category            VARCHAR(50)  NOT NULL DEFAULT 'UNCATEGORISED'
                            CHECK (category IN (
                                'SALARY','GROCERIES','UTILITIES','TRANSPORT','ENTERTAINMENT',
                                'HEALTHCARE','EDUCATION','INSURANCE','RENT','SAVINGS',
                                'TRANSFER','FEES','INTEREST','UNCATEGORISED','OTHER'
                            )),
    counterparty_name   VARCHAR(150),
    counterparty_account VARCHAR(12),
    branch_code         VARCHAR(6),
    status              VARCHAR(20)  NOT NULL DEFAULT 'COMPLETED'
                            CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED','REVERSED')),
    initiated_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMP,
    metadata            JSONB
);

-- ============================================================
-- 3. ASYNC_TRANSACTION_JOBS
--    Tracks long-running transaction history queries (≥5 days).
--    Client polls /transactions/jobs/{jobId} for status.
-- ============================================================
CREATE TABLE async_transaction_jobs (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID         NOT NULL REFERENCES accounts(id),
    customer_id     BIGINT       NOT NULL,
    date_from       DATE         NOT NULL,
    date_to         DATE         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
    result_count    INT,
    error_message   TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMP,
    expires_at      TIMESTAMP    NOT NULL DEFAULT (NOW() + INTERVAL '24 hours')
);

-- ============================================================
-- INDEXES
-- ============================================================
-- Account lookups
CREATE INDEX idx_accounts_customer_id   ON accounts(customer_id);
CREATE INDEX idx_accounts_number        ON accounts(account_number);
CREATE INDEX idx_accounts_status        ON accounts(status);

-- Transaction queries (most common: by account + date, sorted desc)
CREATE INDEX idx_txn_account_date       ON transactions(account_id, initiated_at DESC);
CREATE INDEX idx_txn_reference          ON transactions(reference_number);
CREATE INDEX idx_txn_category           ON transactions(account_id, category);
CREATE INDEX idx_txn_status             ON transactions(status);
CREATE INDEX idx_txn_type               ON transactions(account_id, type);

-- Async job lookups
CREATE INDEX idx_async_account_id       ON async_transaction_jobs(account_id);
CREATE INDEX idx_async_customer_id      ON async_transaction_jobs(customer_id);
CREATE INDEX idx_async_status           ON async_transaction_jobs(status);

-- ============================================================
-- AUTO-UPDATE updated_at ON accounts
-- ============================================================
CREATE OR REPLACE FUNCTION update_accounts_updated_at()
RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_accounts_updated_at
    BEFORE UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION update_accounts_updated_at();

-- ============================================================
-- SEED: Demo accounts for testing
-- Customer ID 1 = default admin customer from customer-service
-- ============================================================
INSERT INTO accounts (customer_id, account_number, account_type, account_name, balance, available_balance, overdraft_limit)
VALUES
  (1, '400000000001', 'CHEQUE',  'ZenithBank Cheque Account',  24850.00, 24850.00, 5000.00),
  (1, '400000000002', 'SAVINGS', 'ZenithBank Savings Account', 89200.50,  89200.50, 0.00);
