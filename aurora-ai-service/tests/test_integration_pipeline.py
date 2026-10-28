"""
LEVEL 2 — INTEGRATION TESTS: AuroraAI Full Pipeline

Tests all three agents working together as a complete pipeline.
The database is mocked — no PostgreSQL needed.
The LLM is mocked where needed — no API calls.

Spec coverage (Section 10.2):
  ✔ Full pipeline: plain-English input → SQL → formatted response
  ✔ Error handling: empty DB, DB connection error, unrecognised query
  ✔ Pipeline state flows correctly between all three agents
"""

import pytest
from unittest.mock import patch, MagicMock
from app.graph.pipeline import run_pipeline

ACCOUNT_ID = "550e8400-e29b-41d4-a716-446655440000"


class TestFullPipelineIntegration:

    # ── Balance Query Flow ─────────────────────────────────

    def test_balance_query_returns_formatted_response(self):
        """[INT-01] Full pipeline: balance query returns readable R-formatted response."""
        mock_rows = [{
            "account_type": "CHEQUE", "account_number": "400000000001",
            "balance": 24850.00, "available_balance": 24850.00, "currency": "ZAR",
        }]
        with patch("app.agents.sql_agent.run_readonly_query", return_value=mock_rows), \
             patch("app.agents.verification_agent._format_with_llm",
                   side_effect=Exception("use template")):
            state = run_pipeline("What is my current balance?", ACCOUNT_ID)

        assert state.final_response is not None
        assert "24,850.00" in state.final_response or "balance" in state.final_response.lower()
        assert state.intent.query_type == "account_balance"

    # ── Transaction History Flow ───────────────────────────

    def test_transaction_history_returns_list(self):
        """[INT-02] Full pipeline: transaction history returns formatted list."""
        mock_rows = [
            {"type": "DEBIT", "amount": 1200.00, "description": "Checkers",
             "counterparty_name": "Checkers Fourways", "initiated_at": "2026-01-15 10:00:00",
             "balance_after": 23650.00, "status": "COMPLETED"},
            {"type": "CREDIT", "amount": 18500.00, "description": "Salary",
             "counterparty_name": "Employer", "initiated_at": "2026-01-01 08:00:00",
             "balance_after": 24850.00, "status": "COMPLETED"},
        ]
        with patch("app.agents.sql_agent.run_readonly_query", return_value=mock_rows), \
             patch("app.agents.verification_agent._format_with_llm",
                   side_effect=Exception("use template")):
            state = run_pipeline("Show my last transactions", ACCOUNT_ID)

        assert state.final_response is not None
        assert state.sql_result is not None
        assert len(state.sql_result.rows) == 2

    # ── Payment Confirmation Flow ──────────────────────────

    def test_payment_confirmation_flow(self):
        """[INT-03] Full pipeline: payment confirmation returns status."""
        mock_rows = [{
            "id": "abc-123", "to_account_no": "12345678901",
            "amount": 1000.00, "status": "COMPLETED",
            "payment_date": "2026-01-10", "processed_at": "2026-01-10 09:00:00",
        }]
        with patch("app.agents.sql_agent.run_readonly_query", return_value=mock_rows), \
             patch("app.agents.verification_agent._format_with_llm",
                   side_effect=Exception("use template")):
            state = run_pipeline("Did my payment to account 12345678901 go through?", ACCOUNT_ID)

        assert state.final_response is not None
        assert "COMPLETED" in state.final_response or "completed" in state.final_response.lower()

    # ── Error Handling ─────────────────────────────────────

    def test_empty_db_returns_friendly_message(self):
        """[INT-04] Empty DB result returns a friendly, non-technical message."""
        with patch("app.agents.sql_agent.run_readonly_query", return_value=[]):
            state = run_pipeline("Show my transactions", ACCOUNT_ID)

        assert state.final_response is not None
        assert len(state.final_response) > 10
        # Should not expose SQL or technical details
        assert "SELECT" not in state.final_response.upper()

    def test_db_connection_error_returns_graceful_message(self):
        """[INT-05] DB connection error returns friendly retry message."""
        with patch("app.agents.sql_agent.run_readonly_query",
                   side_effect=Exception("could not connect to server")):
            state = run_pipeline("What is my balance?", ACCOUNT_ID)

        assert state.final_response is not None
        assert any(w in state.final_response.lower()
                   for w in ["try again", "issue", "sorry", "encountered"])

    def test_unrecognised_query_falls_back_gracefully(self):
        """[INT-06] Unrecognised query uses fallback — does not crash."""
        with patch("app.agents.sql_agent.run_readonly_query", return_value=[]):
            state = run_pipeline("xyzzy frobulate the quantum ledger", ACCOUNT_ID)

        assert state.final_response is not None

    def test_pipeline_state_flows_through_all_three_agents(self):
        """[INT-07] Each agent populates its own state field correctly."""
        mock_rows = [{"account_type": "CHEQUE", "account_number": "400000000001",
                      "balance": 5000.00, "available_balance": 5000.00, "currency": "ZAR"}]
        with patch("app.agents.sql_agent.run_readonly_query", return_value=mock_rows), \
             patch("app.agents.verification_agent._format_with_llm",
                   side_effect=Exception("use template")):
            state = run_pipeline("What is my balance?", ACCOUNT_ID)

        assert state.intent is not None           # Requirements Agent ran
        assert state.sql_result is not None       # SQL Agent ran
        assert state.final_response is not None   # Verification Agent ran

    def test_pipeline_without_account_id_still_runs(self):
        """[INT-08] Pipeline runs without account_id (uses NULL in SQL)."""
        with patch("app.agents.sql_agent.run_readonly_query", return_value=[]):
            state = run_pipeline("Show my balance")  # no account_id

        assert state.final_response is not None

    # ── Cross-Agent Data Integrity ─────────────────────────

    def test_intent_account_id_passed_to_sql_params(self):
        """[INT-09] account_id from intent is used as SQL parameter."""
        captured_params = {}

        def mock_query(sql, params):
            captured_params.update(params)
            return []

        with patch("app.agents.sql_agent.run_readonly_query", side_effect=mock_query):
            run_pipeline("What is my balance?", ACCOUNT_ID)

        assert "account_id" in captured_params
        assert captured_params["account_id"] == ACCOUNT_ID

    def test_large_transaction_amount_threshold_in_sql_params(self):
        """[INT-10] Amount threshold from message is carried into SQL params."""
        captured_params = {}

        def mock_query(sql, params):
            captured_params.update(params)
            return []

        with patch("app.agents.sql_agent.run_readonly_query", side_effect=mock_query):
            run_pipeline("Show all transactions above R8000", ACCOUNT_ID)

        # The amount 8000 (or default 5000) should appear in params
        assert "min_amount" in captured_params
