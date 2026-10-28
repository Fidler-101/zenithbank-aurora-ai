-- ============================================================
-- ZenithBank | Payment Service — Database Schema
-- Database : zenithbank_payment
-- Author   : SP Nkosi
-- ============================================================

DROP TABLE IF EXISTS payment_audit_log CASCADE;
DROP TABLE IF EXISTS payments           CASCADE;
DROP TABLE IF EXISTS branches           CASCADE;

-- ============================================================
-- 1. BRANCHES
--    Reference table of valid bank branch codes.
--    Used to validate recipient branch code on payment creation.
-- ============================================================
CREATE TABLE branches (
    id          BIGSERIAL    PRIMARY KEY,
    branch_code VARCHAR(6)   NOT NULL UNIQUE,
    branch_name VARCHAR(100) NOT NULL,
    bank_name   VARCHAR(100) NOT NULL,
    region      VARCHAR(50)  NOT NULL
);

-- ============================================================
-- 2. PAYMENTS
--    Every outbound payment the customer submits.
--    Supports once-off, future-dated, and recurring payments.
-- ============================================================
CREATE TABLE payments (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id         BIGINT       NOT NULL,
    from_account_id     UUID         NOT NULL,
    from_account_no     VARCHAR(12)  NOT NULL,
    to_account_no       VARCHAR(20)  NOT NULL,
    branch_code         VARCHAR(6)   NOT NULL REFERENCES branches(branch_code),
    amount              NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    reference           VARCHAR(100),
    payment_date        DATE         NOT NULL,
    frequency           VARCHAR(10)  NOT NULL DEFAULT 'ONCE'
                            CHECK (frequency IN ('ONCE','DAILY','WEEKLY','MONTHLY')),
    is_recurring        BOOLEAN      NOT NULL DEFAULT FALSE,
    next_run_date       DATE,
    status              VARCHAR(15)  NOT NULL DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED','CANCELLED')),
    failure_reason      TEXT,
    transaction_id      UUID,
    proof_generated     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    processed_at        TIMESTAMP,
    cancelled_at        TIMESTAMP
);

-- ============================================================
-- 3. PAYMENT_AUDIT_LOG
--    Immutable record of every status change on a payment.
-- ============================================================
CREATE TABLE payment_audit_log (
    id          BIGSERIAL   PRIMARY KEY,
    payment_id  UUID        NOT NULL REFERENCES payments(id),
    from_status VARCHAR(15),
    to_status   VARCHAR(15) NOT NULL,
    reason      TEXT,
    changed_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- ============================================================
-- INDEXES
-- ============================================================
CREATE INDEX idx_payments_customer_id   ON payments(customer_id);
CREATE INDEX idx_payments_status        ON payments(status);
CREATE INDEX idx_payments_payment_date  ON payments(payment_date);
CREATE INDEX idx_payments_next_run      ON payments(next_run_date) WHERE is_recurring = TRUE;
CREATE INDEX idx_audit_payment_id       ON payment_audit_log(payment_id);

-- ============================================================
-- AUTO-UPDATE updated_at
-- ============================================================
CREATE OR REPLACE FUNCTION update_payment_updated_at()
RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payments_updated_at
    BEFORE UPDATE ON payments
    FOR EACH ROW EXECUTE FUNCTION update_payment_updated_at();

-- ============================================================
-- SEED: South African bank branch codes
-- ============================================================
INSERT INTO branches (branch_code, branch_name, bank_name, region) VALUES
  ('250655', 'Standard Bank Head Office',    'Standard Bank',    'Gauteng'),
  ('632005', 'Nedbank Head Office',          'Nedbank',          'Gauteng'),
  ('051001', 'FNB Head Office',              'First National Bank','Gauteng'),
  ('470010', 'ABSA Head Office',             'ABSA',             'Gauteng'),
  ('580105', 'Capitec Head Office',          'Capitec Bank',     'Western Cape'),
  ('462005', 'Investec Head Office',         'Investec Bank',    'Gauteng'),
  ('431010', 'African Bank Head Office',     'African Bank',     'Gauteng'),
  ('410506', 'Discovery Bank Head Office',   'Discovery Bank',   'Gauteng'),
  ('250655', 'Standard Bank Pretoria',       'Standard Bank',    'Gauteng'),
  ('051001', 'FNB Cape Town',                'First National Bank','Western Cape')
ON CONFLICT (branch_code) DO NOTHING;
