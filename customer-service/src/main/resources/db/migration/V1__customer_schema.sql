-- ============================================================
-- ZenithBank | Customer Service — Database Schema
-- Database : zenithbank_customer
-- Covers   : registration, OTP, admin approval, login sessions
-- Author   : SP Nkosi
-- ============================================================

DROP TABLE IF EXISTS refresh_tokens    CASCADE;
DROP TABLE IF EXISTS login_attempts    CASCADE;
DROP TABLE IF EXISTS admin_audit_log   CASCADE;
DROP TABLE IF EXISTS otp_verifications CASCADE;
DROP TABLE IF EXISTS customers         CASCADE;

-- ============================================================
-- 1. CUSTOMERS
--    Single source of truth for all customer identity.
--    Status lifecycle:
--      PENDING → OTP_VERIFIED → ACTIVE (admin approves)
--                             → REJECTED / SUSPENDED
-- ============================================================
CREATE TABLE customers (
    id              BIGSERIAL    PRIMARY KEY,
    first_name      VARCHAR(80)  NOT NULL,
    last_name       VARCHAR(80)  NOT NULL,
    email           VARCHAR(150) NOT NULL UNIQUE,
    phone_number    VARCHAR(20)  NOT NULL UNIQUE,
    id_number       VARCHAR(20)  NOT NULL UNIQUE,
    date_of_birth   DATE         NOT NULL,
    address_line1   VARCHAR(150) NOT NULL,
    address_line2   VARCHAR(150),
    city            VARCHAR(80)  NOT NULL,
    province        VARCHAR(80)  NOT NULL,
    postal_code     VARCHAR(10)  NOT NULL,
    country         VARCHAR(60)  NOT NULL DEFAULT 'South Africa',
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(20)  NOT NULL DEFAULT 'CUSTOMER'
                        CHECK (role IN ('CUSTOMER', 'ADMIN')),
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING','OTP_VERIFIED','ACTIVE','REJECTED','SUSPENDED')),
    email_verified  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    approved_at     TIMESTAMP,
    approved_by     VARCHAR(100),
    last_login_at   TIMESTAMP
);

-- ============================================================
-- 2. OTP_VERIFICATIONS
-- ============================================================
CREATE TABLE otp_verifications (
    id          BIGSERIAL   PRIMARY KEY,
    customer_id BIGINT      NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    otp_code    VARCHAR(6)  NOT NULL,
    otp_type    VARCHAR(30) NOT NULL DEFAULT 'EMAIL_VERIFICATION'
                    CHECK (otp_type IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET', 'LOGIN_2FA')),
    expires_at  TIMESTAMP   NOT NULL,
    used        BOOLEAN     NOT NULL DEFAULT FALSE,
    used_at     TIMESTAMP,
    attempts    INT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- ============================================================
-- 3. REFRESH_TOKENS
--    Stores JWT refresh tokens for session management.
--    Revoked on logout or suspicious activity.
-- ============================================================
CREATE TABLE refresh_tokens (
    id          BIGSERIAL    PRIMARY KEY,
    customer_id BIGINT       NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    device_info VARCHAR(255),
    ip_address  VARCHAR(45),
    expires_at  TIMESTAMP    NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    revoked_at  TIMESTAMP,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ============================================================
-- 4. LOGIN_ATTEMPTS
--    Tracks failed login attempts for brute-force protection.
--    Account locked after 5 consecutive failures.
-- ============================================================
CREATE TABLE login_attempts (
    id          BIGSERIAL   PRIMARY KEY,
    email       VARCHAR(150) NOT NULL,
    ip_address  VARCHAR(45),
    success     BOOLEAN      NOT NULL,
    attempted_at TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- ============================================================
-- 5. ADMIN_AUDIT_LOG
-- ============================================================
CREATE TABLE admin_audit_log (
    id              BIGSERIAL    PRIMARY KEY,
    customer_id     BIGINT       NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    admin_username  VARCHAR(100) NOT NULL,
    action          VARCHAR(50)  NOT NULL
                        CHECK (action IN ('APPROVED','REJECTED','SUSPENDED','REINSTATED','REVIEWED')),
    reason          TEXT,
    action_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ============================================================
-- INDEXES
-- ============================================================
CREATE INDEX idx_customers_email         ON customers(email);
CREATE INDEX idx_customers_status        ON customers(status);
CREATE INDEX idx_otp_customer_type       ON otp_verifications(customer_id, otp_type);
CREATE INDEX idx_refresh_token_hash      ON refresh_tokens(token_hash);
CREATE INDEX idx_login_attempts_email    ON login_attempts(email);
CREATE INDEX idx_audit_customer_id       ON admin_audit_log(customer_id);

-- ============================================================
-- AUTO-UPDATE updated_at
-- ============================================================
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_customers_updated_at
    BEFORE UPDATE ON customers
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
