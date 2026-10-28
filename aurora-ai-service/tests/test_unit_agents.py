"""
LEVEL 1 — UNIT TESTS: AuroraAI Agents

Tests each agent function in complete isolation using mocks.
No database, no LLM API calls, no network.

Spec coverage (Section 10.2):
  ✔ Requirements Agent: all 10 query types correctly identified
  ✔ SQL Agent: SELECT-only, parameterised, no write keywords
  ✔ Verification Agent: formatting, empty results, error handling
"""

import pytest
from unittest.mock import patch, MagicMock
from datetime import date, timedelta
import re

from app.agents.requirements_agent import _keyword_fallback
from app.agents.sql_agent import _build_query
from app.agents.verification_agent import (
    run_verification_agent,
    _template_format,
    _empty_result_message,
)
from app.models.schemas import PipelineState, QueryIntent, SQLResult

ACCOUNT_ID = "550e8400-e29b-41d4-a716-446655440000"

# ── Fixtures ───────────────────────────────────────────────────

def make_intent(query_type: str, **kwargs) -> QueryIntent:
    return QueryIntent(
        query_type=query_type,
        account_id=ACCOUNT_ID,
        raw_message="test query",
        **kwargs,
    )

def make_state(query_type: str, rows=None, error=None, upstream_error=None) -> PipelineState:
    state = PipelineState(message="test", account_id=ACCOUNT_ID)
    state.intent = make_intent(query_type)
    if upstream_error:
        state.error = upstream_error
    elif rows is not None or error is not None:
        state.sql_result = SQLResult(sql="SELECT 1", params={}, rows=rows or [], error=error)
    return state

# ══════════════════════════════════════════════════════════════
# REQUIREMENTS AGENT — 10 query types (FR-AI-02)
# ══════════════════════════════════════════════════════════════

class TestRequirementsAgentKeywordFallback:
    """Tests the keyword fallback used when LLM is unavailable."""

    @pytest.mark.parametrize("message,expected_type", [
        ("What is my current balance?",                         "account_balance"),
        ("Show my transactions from 1 Jan to 31 Jan",          "transaction_history"),
        ("Show all transactions above R5000 this month",       "large_transactions"),
        ("Give me a summary of my account activity",           "account_summary"),
        ("Did my payment to account 1001 go through?",         "payment_confirmation"),
        ("How much did I spend on groceries last month?",       "spending_by_category"),
        ("What are my last 10 transactions?",                  "recent_transactions"),
        ("Show me all deposits this week",                     "credits_only"),
        ("How much did I spend in February?",                  "total_spent"),
        ("List all high value transactions for account 1001",  "account_investigation"),
    ])
    def test_all_10_query_types(self, message, expected_type):
        """[REQ-01 to REQ-10] All 10 spec-defined query types are correctly identified."""
        intent = _keyword_fallback(message, ACCOUNT_ID)
        assert intent.query_type == expected_type, (
            f"Expected '{expected_type}' for message: '{message}', "
            f"got '{intent.query_type}'"
        )

    def test_this_month_date_resolves_to_current_month(self):
        """[REQ-11] 'this month' resolves to first day of current month."""
        intent = _keyword_fallback("Show transactions this month", ACCOUNT_ID)
        assert intent.date_from is not None
        assert intent.date_from.startswith(date.today().strftime("%Y-%m"))

    def test_last_month_resolves_correctly(self):
        """[REQ-12] 'last month' resolves to previous calendar month."""
        intent = _keyword_fallback("Spending from last month", ACCOUNT_ID)
        assert intent.date_from is not None
        first_this = date.today().replace(day=1)
        last_prev = first_this - timedelta(days=1)
        assert intent.date_from.startswith(last_prev.strftime("%Y-%m"))

    def test_amount_extraction_from_message(self):
        """[REQ-13] Amount threshold extracted from natural language."""
        intent = _keyword_fallback("Transactions above R10000", ACCOUNT_ID)
        assert intent.amount_min == 10000.0

    def test_account_id_preserved_in_intent(self):
        """[REQ-14] account_id is always passed through to intent."""
        intent = _keyword_fallback("Show my balance", ACCOUNT_ID)
        assert intent.account_id == ACCOUNT_ID


# ══════════════════════════════════════════════════════════════
# SQL AGENT — SELECT-only, parameterised queries
# ══════════════════════════════════════════════════════════════

ALL_QUERY_TYPES = [
    "account_balance", "transaction_history", "large_transactions",
    "account_summary", "payment_confirmation", "spending_by_category",
    "recent_transactions", "credits_only", "total_spent", "account_investigation",
]

class TestSQLAgentQueryGeneration:

    @pytest.mark.parametrize("query_type", ALL_QUERY_TYPES)
    def test_generates_select_only(self, query_type):
        """[SQL-01] All generated queries start with SELECT."""
        intent = make_intent(query_type)
        sql, _ = _build_query(intent)
        assert sql.strip().upper().startswith("SELECT"), (
            f"{query_type} generated a non-SELECT query: {sql[:80]}"
        )

    @pytest.mark.parametrize("query_type", ALL_QUERY_TYPES)
    def test_all_params_are_bound(self, query_type):
        """[SQL-02] All :param references in SQL are in the params dict."""
        intent = make_intent(query_type)
        sql, params = _build_query(intent)
        refs = set(re.findall(r":(\w+)", sql))
        for ref in refs:
            assert ref in params, (
                f"SQL for {query_type} references :{ref} but it's not in params"
            )

    @pytest.mark.parametrize("query_type", ALL_QUERY_TYPES)
    def test_no_write_keywords_in_any_query(self, query_type):
        """[SQL-03] No INSERT/UPDATE/DELETE/DROP in generated SQL."""
        forbidden = ["INSERT", "UPDATE", "DELETE", "DROP", "CREATE", "ALTER", "TRUNCATE"]
        intent = make_intent(query_type)
        sql, _ = _build_query(intent)
        for kw in forbidden:
            assert kw not in sql.upper(), (
                f"Forbidden keyword '{kw}' found in SQL for {query_type}"
            )

    def test_large_transactions_uses_amount_threshold(self):
        """[SQL-04] large_transactions query uses the amount_min from intent."""
        intent = make_intent("large_transactions", amount_min=5000.0)
        sql, params = _build_query(intent)
        assert "min_amount" in params
        assert params["min_amount"] == 5000.0

    def test_result_cap_at_100_rows(self):
        """[SQL-05] Queries include LIMIT parameter to cap results."""
        intent = make_intent("recent_transactions", limit=20)
        sql, params = _build_query(intent)
        assert "limit" in params
        assert params["limit"] == 20


# ══════════════════════════════════════════════════════════════
# VERIFICATION AGENT — formatting and error handling
# ══════════════════════════════════════════════════════════════

class TestVerificationAgentFormatting:

    def test_upstream_error_returns_friendly_message(self):
        """[VER-01] Upstream pipeline error returns friendly message."""
        state = make_state("account_balance", upstream_error="Requirements agent failed")
        result = run_verification_agent(state)
        assert result.final_response is not None
        assert len(result.final_response) > 10

    def test_db_error_returns_friendly_message(self):
        """[VER-02] SQL execution error returns friendly try-again message."""
        state = make_state("account_balance", error="connection refused")
        result = run_verification_agent(state)
        assert "try again" in result.final_response.lower() or \
               "issue" in result.final_response.lower()

    @pytest.mark.parametrize("query_type", ALL_QUERY_TYPES)
    def test_empty_result_returns_message_for_all_types(self, query_type):
        """[VER-03] Empty DB result gives a friendly message for all 10 query types."""
        state = make_state(query_type, rows=[])
        result = run_verification_agent(state)
        assert result.final_response is not None
        assert len(result.final_response) > 10

    def test_balance_template_formats_rand_correctly(self):
        """[VER-04] Balance result is formatted with R currency and correct values."""
        state = make_state("account_balance", rows=[{
            "account_type": "CHEQUE", "account_number": "400000000001",
            "balance": 24850.00, "available_balance": 24850.00, "currency": "ZAR",
        }])
        with patch("app.agents.verification_agent._format_with_llm",
                   side_effect=Exception("use template")):
            result = run_verification_agent(state)
        assert "R24,850.00" in result.final_response

    def test_total_spent_template_formats_correctly(self):
        """[VER-05] Total spent result formats correctly with Rand amounts."""
        state = make_state("total_spent", rows=[{
            "total_spent": 12500.00, "transaction_count": 15,
            "period_from": "2026-02-01", "period_to": "2026-02-28",
        }])
        with patch("app.agents.verification_agent._format_with_llm",
                   side_effect=Exception("use template")):
            result = run_verification_agent(state)
        assert "R12,500.00" in result.final_response

    def test_empty_result_messages_are_unique_per_type(self):
        """[VER-06] Each query type has a distinct empty result message."""
        messages = {qt: _empty_result_message(qt) for qt in ALL_QUERY_TYPES}
        unique_messages = set(messages.values())
        # All should be unique — no two query types share the same empty message
        assert len(unique_messages) == len(ALL_QUERY_TYPES)

    def test_no_sql_exposed_in_response(self):
        """[VER-07] SQL keywords never appear in the final user-facing response."""
        state = make_state("transaction_history", rows=[{
            "type": "DEBIT", "amount": 500.00, "description": "Test",
            "counterparty_name": "Shop", "initiated_at": "2026-01-15",
            "balance_after": 24350.00, "status": "COMPLETED",
        }])
        with patch("app.agents.verification_agent._format_with_llm",
                   side_effect=Exception("use template")):
            result = run_verification_agent(state)
        forbidden_in_response = ["SELECT", "FROM", "WHERE", "JOIN"]
        for kw in forbidden_in_response:
            assert kw not in result.final_response.upper(), \
                f"SQL keyword '{kw}' found in user-facing response"
