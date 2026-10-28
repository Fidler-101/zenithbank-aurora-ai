"""
AuroraAI — Pytest Test Suite

Covers:
  - Requirements Agent: 10 sample queries → correct intent extracted
  - SQL Agent: generated SQL is valid, parameterised, SELECT-only
  - Verification Agent: formatting, empty result handling, error handling
  - Full pipeline: end-to-end with a mock DB
  - Error handling: empty results, DB error, unrecognised query
"""

import pytest
from unittest.mock import patch, MagicMock
from app.agents.requirements_agent import _keyword_fallback
from app.agents.sql_agent import run_sql_agent, _build_query
from app.agents.verification_agent import run_verification_agent, _empty_result_message
from app.models.schemas import PipelineState, QueryIntent, SQLResult


# ── Fixtures ──────────────────────────────────────────────────

SAMPLE_ACCOUNT_ID = "550e8400-e29b-41d4-a716-446655440000"

def make_state(query_type: str, rows: list = None, error: str = None) -> PipelineState:
    """Helper to build a PipelineState with a pre-set intent and SQL result."""
    state = PipelineState(
        message="test query",
        account_id=SAMPLE_ACCOUNT_ID,
    )
    state.intent = QueryIntent(
        query_type=query_type,
        account_id=SAMPLE_ACCOUNT_ID,
        raw_message="test query",
    )
    if rows is not None or error is not None:
        state.sql_result = SQLResult(
            sql="SELECT 1", params={}, rows=rows or [], error=error
        )
    return state


# ══════════════════════════════════════════════════════════════
# REQUIREMENTS AGENT — FR-AI-02: all 10 query types
# ══════════════════════════════════════════════════════════════

class TestRequirementsAgent:

    def test_balance_query(self):
        intent = _keyword_fallback("What is my current balance?", SAMPLE_ACCOUNT_ID)
        assert intent.query_type == "account_balance"

    def test_transaction_history_by_date(self):
        intent = _keyword_fallback("Show my transactions from 1 Jan to 31 Jan", SAMPLE_ACCOUNT_ID)
        assert intent.query_type == "transaction_history"

    def test_large_transactions(self):
        intent = _keyword_fallback("Show all transactions above R5000 this month", SAMPLE_ACCOUNT_ID)
        assert intent.query_type == "large_transactions"
        assert intent.amount_min == 5000.0

    def test_account_summary(self):
        intent = _keyword_fallback("Give me a summary of my account activity", SAMPLE_ACCOUNT_ID)
        assert intent.query_type == "account_summary"

    def test_payment_confirmation(self):
        intent = _keyword_fallback("Did my payment to account 1001 go through?", SAMPLE_ACCOUNT_ID)
        assert intent.query_type == "payment_confirmation"

    def test_spending_by_category(self):
        intent = _keyword_fallback("How much did I spend on groceries last month?", SAMPLE_ACCOUNT_ID)
        assert intent.query_type == "spending_by_category"

    def test_recent_transactions(self):
        intent = _keyword_fallback("What are my last 10 transactions?", SAMPLE_ACCOUNT_ID)
        assert intent.query_type == "recent_transactions"

    def test_credits_only(self):
        intent = _keyword_fallback("Show me all deposits this week", SAMPLE_ACCOUNT_ID)
        assert intent.query_type == "credits_only"

    def test_total_spent(self):
        intent = _keyword_fallback("How much did I spend in February?", SAMPLE_ACCOUNT_ID)
        assert intent.query_type == "total_spent"

    def test_account_investigation(self):
        intent = _keyword_fallback(
            "List all transactions for account 1001 flagged as high value", SAMPLE_ACCOUNT_ID
        )
        assert intent.query_type == "account_investigation"

    def test_this_month_date_resolution(self):
        from datetime import date
        intent = _keyword_fallback("Show transactions this month", SAMPLE_ACCOUNT_ID)
        assert intent.date_from is not None
        assert intent.date_from.startswith(date.today().strftime("%Y-%m"))

    def test_last_30_days_resolution(self):
        intent = _keyword_fallback("Show my last 30 days of transactions", SAMPLE_ACCOUNT_ID)
        assert intent.date_from is not None

    def test_amount_extraction(self):
        intent = _keyword_fallback("Transactions above R10000", SAMPLE_ACCOUNT_ID)
        assert intent.amount_min == 10000.0


# ══════════════════════════════════════════════════════════════
# SQL AGENT — queries must be SELECT-only and parameterised
# ══════════════════════════════════════════════════════════════

class TestSQLAgent:

    ALL_QUERY_TYPES = [
        "account_balance", "transaction_history", "large_transactions",
        "account_summary", "payment_confirmation", "spending_by_category",
        "recent_transactions", "credits_only", "total_spent", "account_investigation",
    ]

    @pytest.mark.parametrize("query_type", ALL_QUERY_TYPES)
    def test_generates_select_only(self, query_type):
        """Every generated query must start with SELECT."""
        intent = QueryIntent(
            query_type=query_type,
            account_id=SAMPLE_ACCOUNT_ID,
            raw_message="test",
        )
        sql, _ = _build_query(intent)
        assert sql.strip().upper().startswith("SELECT"), \
            f"{query_type} generated a non-SELECT query: {sql[:60]}"

    @pytest.mark.parametrize("query_type", ALL_QUERY_TYPES)
    def test_uses_bound_parameters(self, query_type):
        """Every query must use :param syntax — no string interpolation."""
        intent = QueryIntent(
            query_type=query_type,
            account_id=SAMPLE_ACCOUNT_ID,
            raw_message="test",
        )
        sql, params = _build_query(intent)
        # All params referenced in the SQL must appear in the params dict
        import re
        refs = set(re.findall(r":(\w+)", sql))
        for ref in refs:
            assert ref in params, \
                f"SQL references :{ref} but it's not in params dict for {query_type}"

    def test_no_write_keywords_in_generated_sql(self):
        """No write operations should appear in any generated SQL."""
        forbidden = ["INSERT", "UPDATE", "DELETE", "DROP", "CREATE", "ALTER", "TRUNCATE"]
        for qt in self.ALL_QUERY_TYPES:
            intent = QueryIntent(query_type=qt, account_id=SAMPLE_ACCOUNT_ID, raw_message="t")
            sql, _ = _build_query(intent)
            for kw in forbidden:
                assert kw not in sql.upper(), \
                    f"Forbidden keyword '{kw}' found in {qt} SQL"

    def test_sql_agent_handles_db_error(self):
        """SQL Agent must mark the error in state without raising."""
        state = PipelineState(
            message="What is my balance?",
            account_id=SAMPLE_ACCOUNT_ID,
        )
        state.intent = QueryIntent(
            query_type="account_balance",
            account_id=SAMPLE_ACCOUNT_ID,
            raw_message="What is my balance?",
        )

        with patch("app.agents.sql_agent.run_readonly_query",
                   side_effect=Exception("DB connection refused")):
            result = run_sql_agent(state)

        assert result.sql_result is not None
        assert result.sql_result.error is not None
        assert "DB connection refused" in result.sql_result.error


# ══════════════════════════════════════════════════════════════
# VERIFICATION AGENT — formatting and error handling
# ══════════════════════════════════════════════════════════════

class TestVerificationAgent:

    def test_empty_result_returns_friendly_message(self):
        state = make_state("account_balance", rows=[])
        result = run_verification_agent(state)
        assert result.final_response is not None
        assert len(result.final_response) > 0

    def test_db_error_returns_friendly_message(self):
        state = make_state("account_balance", error="connection refused")
        result = run_verification_agent(state)
        assert "try again" in result.final_response.lower() or \
               "sorry" in result.final_response.lower()

    def test_upstream_error_handled(self):
        state = PipelineState(
            message="test", account_id=SAMPLE_ACCOUNT_ID,
            error="Requirements agent failed"
        )
        result = run_verification_agent(state)
        assert result.final_response is not None

    def test_balance_template_format(self):
        state = make_state("account_balance", rows=[{
            "account_type": "CHEQUE",
            "account_number": "400000000001",
            "balance": 24850.00,
            "available_balance": 24850.00,
            "currency": "ZAR",
        }])
        with patch("app.agents.verification_agent._format_with_llm",
                   side_effect=Exception("LLM unavailable")):
            result = run_verification_agent(state)
        assert "R24,850.00" in result.final_response
        assert "CHEQUE" in result.final_response

    def test_total_spent_template_format(self):
        state = make_state("total_spent", rows=[{
            "total_spent": 12500.00,
            "transaction_count": 15,
            "period_from": "2026-02-01",
            "period_to": "2026-02-28",
        }])
        with patch("app.agents.verification_agent._format_with_llm",
                   side_effect=Exception("LLM unavailable")):
            result = run_verification_agent(state)
        assert "R12,500.00" in result.final_response

    @pytest.mark.parametrize("query_type", [
        "account_balance", "transaction_history", "large_transactions",
        "account_summary", "payment_confirmation", "spending_by_category",
        "recent_transactions", "credits_only", "total_spent", "account_investigation",
    ])
    def test_empty_result_messages_all_query_types(self, query_type):
        msg = _empty_result_message(query_type)
        assert isinstance(msg, str)
        assert len(msg) > 10


# ══════════════════════════════════════════════════════════════
# FULL PIPELINE — end-to-end with mock DB
# ══════════════════════════════════════════════════════════════

class TestFullPipeline:

    def test_pipeline_balance_query_end_to_end(self):
        """End-to-end: plain-English balance query → readable response."""
        mock_rows = [{
            "account_type": "CHEQUE",
            "account_number": "400000000001",
            "balance": 24850.00,
            "available_balance": 24850.00,
            "currency": "ZAR",
        }]

        with patch("app.agents.sql_agent.run_readonly_query", return_value=mock_rows), \
             patch("app.agents.verification_agent._format_with_llm",
                   side_effect=Exception("use template")):

            from app.graph.pipeline import run_pipeline
            state = run_pipeline(
                message="What is my current balance?",
                account_id=SAMPLE_ACCOUNT_ID,
            )

        assert state.final_response is not None
        assert "24,850.00" in state.final_response or "balance" in state.final_response.lower()

    def test_pipeline_empty_db_returns_friendly_message(self):
        """If the DB returns no rows, the pipeline still returns a friendly message."""
        with patch("app.agents.sql_agent.run_readonly_query", return_value=[]):
            from app.graph.pipeline import run_pipeline
            state = run_pipeline(
                message="Show my transactions",
                account_id=SAMPLE_ACCOUNT_ID,
            )
        assert state.final_response is not None
        assert len(state.final_response) > 0

    def test_pipeline_db_connection_error(self):
        """If the DB is unreachable, the pipeline returns a graceful error message."""
        with patch("app.agents.sql_agent.run_readonly_query",
                   side_effect=Exception("could not connect to server")):
            from app.graph.pipeline import run_pipeline
            state = run_pipeline(
                message="What is my balance?",
                account_id=SAMPLE_ACCOUNT_ID,
            )
        assert state.final_response is not None
        assert "try again" in state.final_response.lower() or \
               "issue" in state.final_response.lower() or \
               "sorry" in state.final_response.lower()

    def test_pipeline_unrecognised_query_falls_back(self):
        """An unrecognised query should not crash — it falls back to transaction_history."""
        mock_rows = []
        with patch("app.agents.sql_agent.run_readonly_query", return_value=mock_rows):
            from app.graph.pipeline import run_pipeline
            state = run_pipeline(
                message="xyzzy frobulate the quantum ledger",
                account_id=SAMPLE_ACCOUNT_ID,
            )
        assert state.final_response is not None
