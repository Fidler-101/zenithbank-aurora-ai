"""
AuroraAI — SQL Agent (Agent 2)

Converts the structured QueryIntent from the Requirements Agent
into a safe, parameterised SQL query and executes it against
the shared read-only PostgreSQL database.

Safety rules (enforced here AND at the DB layer):
  - Only SELECT statements are generated — no INSERT/UPDATE/DELETE/DROP
  - All user-supplied values go through SQLAlchemy bound parameters
  - Query results are capped at 100 rows to prevent runaway responses
  - Queries are scoped to the customer's own account_id where applicable
"""

import logging
import re
from datetime import date, timedelta
from app.models.schemas import QueryIntent, SQLResult, PipelineState
from app.db.connection import run_readonly_query

logger = logging.getLogger(__name__)

MAX_ROWS = 100


def run_sql_agent(state: PipelineState) -> PipelineState:
    """
    SQL Agent entry point.
    Builds and executes a parameterised SELECT query for the detected intent.
    """
    if state.intent is None:
        state.error = "No intent available — Requirements Agent may have failed."
        return state

    intent = state.intent
    logger.info("SQL Agent — building query for type: %s", intent.query_type)

    # "greeting" and "unclear" aren't real data questions -- there is
    # nothing to query. Skip the database entirely and return an empty
    # result; the Verification Agent already has a friendly canned
    # message for both. Without this check, both types previously fell
    # through to the default case below and silently returned an
    # unrelated transaction list.
    if intent.query_type in ("greeting", "unclear"):
        state.sql_result = SQLResult(sql="", params={}, rows=[])
        return state

    try:
        customer_id = _clean_customer_id(state.customer_id)
        if customer_id is None:
            logger.warning("SQL Agent — no verified customer id; query will return no rows")
        _normalise_intent(intent)
        sql, params = _build_query(intent, customer_id)
        logger.debug("SQL Agent — executing: %s | params: %s", sql.strip(), params)

        rows = run_readonly_query(sql, params)

        state.sql_result = SQLResult(
            sql=sql,
            params=params,
            rows=rows[:MAX_ROWS],
        )
        logger.info("SQL Agent — returned %d row(s)", len(rows))

    except Exception as e:
        logger.error("SQL Agent — query failed: %s", e)
        state.sql_result = SQLResult(sql="", params={}, error=str(e))

    return state


def _build_query(intent: QueryIntent, customer_id: int | None = None) -> tuple[str, dict]:
    """Builds the query for the intent and scopes it to the verified customer."""
    sql, params = _build_query_unscoped(intent)
    return _scope_to_customer(sql, params, customer_id)


def _build_query_unscoped(intent: QueryIntent) -> tuple[str, dict]:
    """
    Maps each of the 10 query types to a parameterised SQL query.
    All queries are SELECT-only and scoped to the customer's account where relevant.
    """
    today      = date.today().isoformat()
    month_start = date.today().replace(day=1).isoformat()

    # Resolve default dates
    date_from = intent.date_from or month_start
    date_to   = intent.date_to   or today

    match intent.query_type:

        # 1. Account balance
        case "account_balance":
            sql = """
                SELECT account_number, account_type, balance, available_balance, currency
                FROM accounts
                WHERE (:account_id IS NULL OR id::text = :account_id)
                  AND status = 'ACTIVE'
                LIMIT 5
            """
            return sql, {"account_id": intent.account_id}

        # 2. Transaction history by date range
        case "transaction_history":
            sql = """
                SELECT t.id, t.type, t.amount, t.description, t.category,
                       t.counterparty_name, t.status, t.initiated_at, t.balance_after
                FROM transactions t
                JOIN accounts a ON t.account_id = a.id
                WHERE (:account_id IS NULL OR a.id::text = :account_id)
                  AND t.initiated_at BETWEEN :date_from AND :date_to
                ORDER BY t.initiated_at DESC
                LIMIT :limit
            """
            return sql, {
                "account_id": intent.account_id,
                "date_from":  date_from + " 00:00:00",
                "date_to":    date_to   + " 23:59:59",
                "limit":      intent.limit,
            }

        # 3. Large transactions above a threshold
        case "large_transactions":
            min_amount = intent.amount_min or 5000
            sql = """
                SELECT t.id, t.type, t.amount, t.description, t.counterparty_name,
                       t.status, t.initiated_at, a.account_number
                FROM transactions t
                JOIN accounts a ON t.account_id = a.id
                WHERE (:account_id IS NULL OR a.id::text = :account_id)
                  AND t.amount >= :min_amount
                  AND t.initiated_at BETWEEN :date_from AND :date_to
                ORDER BY t.amount DESC
                LIMIT :limit
            """
            return sql, {
                "account_id": intent.account_id,
                "min_amount": min_amount,
                "date_from":  date_from + " 00:00:00",
                "date_to":    date_to   + " 23:59:59",
                "limit":      intent.limit,
            }

        # 4. Account summary (balance + transaction count + totals)
        case "account_summary":
            sql = """
                SELECT
                    a.account_number, a.account_type, a.balance, a.available_balance,
                    COUNT(t.id)                                          AS transaction_count,
                    COALESCE(SUM(CASE WHEN t.type IN ('CREDIT','TRANSFER_IN','INTEREST')
                                      THEN t.amount ELSE 0 END), 0)    AS total_credits,
                    COALESCE(SUM(CASE WHEN t.type IN ('DEBIT','TRANSFER_OUT','FEE')
                                      THEN t.amount ELSE 0 END), 0)    AS total_debits
                FROM accounts a
                LEFT JOIN transactions t ON t.account_id = a.id
                    AND t.initiated_at BETWEEN :date_from AND :date_to
                    AND t.status = 'COMPLETED'
                WHERE (:account_id IS NULL OR a.id::text = :account_id)
                  AND a.status = 'ACTIVE'
                GROUP BY a.id, a.account_number, a.account_type, a.balance, a.available_balance
            """
            return sql, {
                "account_id": intent.account_id,
                "date_from":  date_from + " 00:00:00",
                "date_to":    date_to   + " 23:59:59",
            }

        # 5. Payment confirmation -- read from the banking ledger. Payments are executed as
        #    TRANSFER_OUT ledger entries, and the payment-service's own payments table lives in a
        #    different database that AuroraAI deliberately cannot reach.
        case "payment_confirmation":
            sql = """
                SELECT t.id, t.reference_number, t.amount, t.description,
                       t.counterparty_name, t.counterparty_account, t.branch_code,
                       t.status, t.initiated_at, t.completed_at,
                       t.fraud_risk_score, t.is_flagged
                FROM transactions t
                JOIN accounts a ON t.account_id = a.id
                WHERE (:account_id IS NULL OR a.id::text = :account_id)
                  AND t.type = 'TRANSFER_OUT'
                  AND t.initiated_at BETWEEN :date_from AND :date_to
                ORDER BY t.initiated_at DESC
                LIMIT :limit
            """
            return sql, {
                "account_id": intent.account_id,
                "date_from":  date_from + " 00:00:00",
                "date_to":    date_to   + " 23:59:59",
                "limit":      intent.limit,
            }

        # 6. Spending by category
        case "spending_by_category":
            category_filter = f"AND t.category = :category" if intent.description else ""
            sql = f"""
                SELECT t.category,
                       COUNT(t.id)    AS count,
                       SUM(t.amount)  AS total_amount
                FROM transactions t
                JOIN accounts a ON t.account_id = a.id
                WHERE (:account_id IS NULL OR a.id::text = :account_id)
                  AND t.type IN ('DEBIT','TRANSFER_OUT','FEE')
                  AND t.status = 'COMPLETED'
                  AND t.initiated_at BETWEEN :date_from AND :date_to
                  {category_filter}
                GROUP BY t.category
                ORDER BY total_amount DESC
            """
            params = {
                "account_id": intent.account_id,
                "date_from":  date_from + " 00:00:00",
                "date_to":    date_to   + " 23:59:59",
            }
            if intent.description:
                params["category"] = intent.description.upper()
            return sql, params

        # 7. Recent transactions (last N)
        case "recent_transactions":
            sql = """
                SELECT t.id, t.type, t.amount, t.description, t.category,
                       t.counterparty_name, t.status, t.initiated_at, t.balance_after
                FROM transactions t
                JOIN accounts a ON t.account_id = a.id
                WHERE (:account_id IS NULL OR a.id::text = :account_id)
                ORDER BY t.initiated_at DESC
                LIMIT :limit
            """
            return sql, {"account_id": intent.account_id, "limit": intent.limit}

        # 8. Credits / deposits only
        case "credits_only":
            sql = """
                SELECT t.id, t.amount, t.description, t.counterparty_name,
                       t.initiated_at, t.balance_after
                FROM transactions t
                JOIN accounts a ON t.account_id = a.id
                WHERE (:account_id IS NULL OR a.id::text = :account_id)
                  AND t.type IN ('CREDIT','TRANSFER_IN','INTEREST')
                  AND t.status = 'COMPLETED'
                  AND t.initiated_at BETWEEN :date_from AND :date_to
                ORDER BY t.initiated_at DESC
                LIMIT :limit
            """
            return sql, {
                "account_id": intent.account_id,
                "date_from":  date_from + " 00:00:00",
                "date_to":    date_to   + " 23:59:59",
                "limit":      intent.limit,
            }

        # 9. Total spent in a period
        case "total_spent":
            sql = """
                SELECT
                    COALESCE(SUM(t.amount), 0) AS total_spent,
                    COUNT(t.id)                AS transaction_count,
                    MIN(t.initiated_at)        AS period_from,
                    MAX(t.initiated_at)        AS period_to
                FROM transactions t
                JOIN accounts a ON t.account_id = a.id
                WHERE (:account_id IS NULL OR a.id::text = :account_id)
                  AND t.type IN ('DEBIT','TRANSFER_OUT','FEE')
                  AND t.status = 'COMPLETED'
                  AND t.initiated_at BETWEEN :date_from AND :date_to
            """
            return sql, {
                "account_id": intent.account_id,
                "date_from":  date_from + " 00:00:00",
                "date_to":    date_to   + " 23:59:59",
            }

        # 10. Staff account investigation — all transactions for a given account
        case "account_investigation":
            min_amount = intent.amount_min or 0
            sql = """
                SELECT t.id, t.type, t.amount, t.description, t.category,
                       t.counterparty_name, t.counterparty_account, t.status,
                       t.initiated_at, a.account_number
                FROM transactions t
                JOIN accounts a ON t.account_id = a.id
                WHERE (:account_id IS NULL OR a.id::text = :account_id OR
                       a.account_number = :account_id)
                  AND t.amount >= :min_amount
                  AND t.initiated_at BETWEEN :date_from AND :date_to
                ORDER BY t.amount DESC, t.initiated_at DESC
                LIMIT :limit
            """
            return sql, {
                "account_id": intent.account_id,
                "min_amount": min_amount,
                "date_from":  date_from + " 00:00:00",
                "date_to":    date_to   + " 23:59:59",
                "limit":      intent.limit,
            }

        case _:
            # Default — recent transactions
            sql = """
                SELECT t.id, t.type, t.amount, t.description, t.initiated_at
                FROM transactions t
                JOIN accounts a ON t.account_id = a.id
                WHERE (:account_id IS NULL OR a.id::text = :account_id)
                ORDER BY t.initiated_at DESC
                LIMIT 10
            """
            return sql, {"account_id": intent.account_id}


# --------------------------------------------------------------------------
# Customer scoping (data isolation)
# --------------------------------------------------------------------------
_OWNER_GUARD = re.compile(r"\(:account_id IS NULL OR (a\.)?id::text")


def _clean_customer_id(value) -> int | None:
    """Accepts only a plain positive integer (the gateway-verified id); anything else -> None."""
    if value is None:
        return None
    text_value = str(value).strip()
    return int(text_value) if text_value.isdigit() and int(text_value) > 0 else None


def _scope_to_customer(sql: str, params: dict, customer_id) -> tuple[str, dict]:
    """
    Adds `AND <accounts>.customer_id = :customer_id` to the ownership clause of every query.
    Fails closed: with no customer id the parameter is NULL, the comparison is never true
    and the query returns no rows. The assertion guarantees no query can skip the filter.
    """
    scoped, n = _OWNER_GUARD.subn(lambda m: f"{m.group(1) or ''}customer_id = :customer_id AND (:account_id IS NULL OR {m.group(1) or ''}id::text", sql)
    assert n >= 1 and ":customer_id" in scoped, "query is not scoped to a customer"
    return scoped, {**params, "customer_id": customer_id}


# --------------------------------------------------------------------------
# Deterministic correction of one known classifier weakness
# --------------------------------------------------------------------------
_LAST_N = re.compile(r"\b(?:last|latest|recent)\s+(\d{1,3})?\s*transactions?\b", re.IGNORECASE)
_HAS_PERIOD = re.compile(r"\b(from|between|since|until|month|week|year|days?|today|yesterday|"
                         r"january|february|march|april|may|june|july|august|september|october|november|december)\b",
                         re.IGNORECASE)


def _normalise_intent(intent: QueryIntent) -> None:
    """'Show my last 5 transactions' is a recent_transactions question with limit 5. The small local
    model sometimes labels it transaction_history, so the unambiguous phrasing is fixed here."""
    message = intent.raw_message or ""
    m = _LAST_N.search(message)
    if not m or _HAS_PERIOD.search(message):
        return
    if intent.query_type in ("transaction_history", "recent_transactions"):
        intent.query_type = "recent_transactions"
        if m.group(1):
            intent.limit = min(int(m.group(1)), MAX_ROWS)
