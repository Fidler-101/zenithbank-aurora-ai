-- ============================================================
-- ZenithBank | Banking Service — V2 Migration
-- Adds data-driven fraud-risk fields to the transaction ledger,
-- and seeds realistic transaction history (including one
-- deliberately suspicious transaction) for the demo accounts
-- created in V1.
--
-- NOTE: transactions is an append-only ledger (see Transaction.java
-- javadoc) — these two columns are computed ONCE at transaction
-- creation time (see TransactionService.recordDebit) and never
-- updated afterwards, consistent with that immutability rule.
-- ============================================================

ALTER TABLE transactions
    ADD COLUMN fraud_risk_score NUMERIC(5,4) NOT NULL DEFAULT 0.0000,
    ADD COLUMN is_flagged       BOOLEAN      NOT NULL DEFAULT FALSE;

CREATE INDEX idx_txn_is_flagged ON transactions(account_id, is_flagged);

-- ============================================================
-- SEED: realistic transaction history for the two demo accounts
-- ============================================================

-- Account 400000000001 (Cheque, balance 24 850.00)
INSERT INTO transactions
    (account_id, reference_number, type, amount, balance_after, description,
     category, counterparty_name, counterparty_account, branch_code,
     status, initiated_at, completed_at, fraud_risk_score, is_flagged)
VALUES
    ((SELECT id FROM accounts WHERE account_number = '400000000001'),
     'ZB1000000001', 'CREDIT', 30000.00, 30000.00, 'Monthly salary deposit',
     'SALARY', 'Umbrella Corp (Pty) Ltd', NULL, NULL,
     'COMPLETED', NOW() - INTERVAL '6 days', NOW() - INTERVAL '6 days', 0.02, FALSE),

    ((SELECT id FROM accounts WHERE account_number = '400000000001'),
     'ZB1000000002', 'DEBIT', 8000.00, 22000.00, 'Rent payment',
     'RENT', 'Green Valley Properties', '400099990001', '198765',
     'COMPLETED', NOW() - INTERVAL '5 days', NOW() - INTERVAL '5 days', 0.03, FALSE),

    ((SELECT id FROM accounts WHERE account_number = '400000000001'),
     'ZB1000000003', 'DEBIT', 1200.00, 20800.00, 'Checkers Groceries',
     'GROCERIES', 'Checkers Sandton', NULL, NULL,
     'COMPLETED', NOW() - INTERVAL '4 days', NOW() - INTERVAL '4 days', 0.01, FALSE),

    -- Deliberately suspicious: large transfer that drains almost the
    -- entire account balance in one go — the same "account drained"
    -- signature used in the fraud-risk heuristic (see Q5.2 pseudocode).
    ((SELECT id FROM accounts WHERE account_number = '400000000001'),
     'ZB1000000004', 'TRANSFER_OUT', 19800.00, 1000.00, 'Transfer to external account',
     'TRANSFER', 'Unknown Beneficiary', '511122223333', '250655',
     'COMPLETED', NOW() - INTERVAL '2 days', NOW() - INTERVAL '2 days', 0.93, TRUE),

    ((SELECT id FROM accounts WHERE account_number = '400000000001'),
     'ZB1000000005', 'CREDIT', 23850.00, 24850.00, 'Emergency top-up transfer',
     'TRANSFER', 'Sifiso Nkosi (own account)', NULL, NULL,
     'COMPLETED', NOW() - INTERVAL '1 days', NOW() - INTERVAL '1 days', 0.05, FALSE);

-- Account 400000000002 (Savings, balance 89 200.50)
INSERT INTO transactions
    (account_id, reference_number, type, amount, balance_after, description,
     category, counterparty_name, counterparty_account, branch_code,
     status, initiated_at, completed_at, fraud_risk_score, is_flagged)
VALUES
    ((SELECT id FROM accounts WHERE account_number = '400000000002'),
     'ZB2000000001', 'CREDIT', 80000.00, 80000.00, 'Opening deposit',
     'SAVINGS', NULL, NULL, NULL,
     'COMPLETED', NOW() - INTERVAL '10 days', NOW() - INTERVAL '10 days', 0.01, FALSE),

    ((SELECT id FROM accounts WHERE account_number = '400000000002'),
     'ZB2000000002', 'INTEREST', 200.50, 80200.50, 'Monthly interest',
     'INTEREST', NULL, NULL, NULL,
     'COMPLETED', NOW() - INTERVAL '5 days', NOW() - INTERVAL '5 days', 0.00, FALSE),

    ((SELECT id FROM accounts WHERE account_number = '400000000002'),
     'ZB2000000003', 'CREDIT', 9000.00, 89200.50, 'Transfer from Cheque account',
     'TRANSFER', 'Sifiso Nkosi (own account)', NULL, NULL,
     'COMPLETED', NOW() - INTERVAL '3 days', NOW() - INTERVAL '3 days', 0.02, FALSE);
