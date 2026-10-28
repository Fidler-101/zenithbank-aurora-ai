"""
LEVEL 3 — SYSTEM TESTS: AuroraAI HTTP Endpoints

Tests the complete HTTP layer of the AuroraAI FastAPI application.
Uses pytest + httpx TestClient — no real DB or LLM needed.

Spec coverage:
  ✔ POST /api/aurora/chat — all inputs, response shapes, error codes
  ✔ GET  /api/aurora/health — service and DB health reporting
  ✔ Response time NFR: must complete within 10 seconds
"""

import pytest
import time
from unittest.mock import patch
from httpx import AsyncClient
from fastapi.testclient import TestClient

from app.main import app

ACCOUNT_ID = "550e8400-e29b-41d4-a716-446655440000"

@pytest.fixture(scope="module")
def client():
    return TestClient(app)


class TestChatEndpoint:
    """POST /api/aurora/chat"""

    def test_chat_returns_200_with_valid_request(self, client):
        """[SYS-01] Valid chat request returns 200 with response field."""
        mock_rows = [{"account_type": "CHEQUE", "account_number": "400000000001",
                      "balance": 24850.00, "available_balance": 24850.00, "currency": "ZAR"}]
        with patch("app.agents.sql_agent.run_readonly_query", return_value=mock_rows), \
             patch("app.agents.verification_agent._format_with_llm",
                   side_effect=Exception("use template")):
            response = client.post("/api/aurora/chat", json={
                "message": "What is my current balance?",
                "account_id": ACCOUNT_ID,
            })

        assert response.status_code == 200
        body = response.json()
        assert "response" in body
        assert "query_type" in body
        assert "result_count" in body
        assert "generated_at" in body

    def test_chat_returns_400_for_empty_message(self, client):
        """[SYS-02] Empty message returns 422 Unprocessable Entity."""
        response = client.post("/api/aurora/chat", json={"message": ""})
        assert response.status_code == 422

    def test_chat_returns_400_for_missing_message(self, client):
        """[SYS-03] Missing message field returns 422."""
        response = client.post("/api/aurora/chat", json={"account_id": ACCOUNT_ID})
        assert response.status_code == 422

    def test_chat_handles_db_error_gracefully(self, client):
        """[SYS-04] DB connection error returns 200 with graceful message (not 500)."""
        with patch("app.agents.sql_agent.run_readonly_query",
                   side_effect=Exception("could not connect to server")):
            response = client.post("/api/aurora/chat", json={
                "message": "What is my balance?",
                "account_id": ACCOUNT_ID,
            })

        assert response.status_code == 200
        body = response.json()
        assert "response" in body
        assert len(body["response"]) > 0

    def test_chat_empty_result_returns_friendly_message(self, client):
        """[SYS-05] Empty DB result returns 200 with friendly message."""
        with patch("app.agents.sql_agent.run_readonly_query", return_value=[]):
            response = client.post("/api/aurora/chat", json={
                "message": "Show my recent transactions",
                "account_id": ACCOUNT_ID,
            })

        assert response.status_code == 200
        assert len(response.json()["response"]) > 0

    def test_chat_without_account_id_still_works(self, client):
        """[SYS-06] Chat works without account_id (returns general response)."""
        with patch("app.agents.sql_agent.run_readonly_query", return_value=[]):
            response = client.post("/api/aurora/chat", json={
                "message": "Show my balance",
            })

        assert response.status_code == 200

    def test_chat_response_time_within_nfr(self, client):
        """[SYS-07] NFR-P-03: Response must complete within 10 seconds."""
        with patch("app.agents.sql_agent.run_readonly_query", return_value=[]):
            start = time.time()
            response = client.post("/api/aurora/chat", json={
                "message": "What is my balance?",
                "account_id": ACCOUNT_ID,
            })
            elapsed = time.time() - start

        assert response.status_code == 200
        assert elapsed < 10.0, f"Response took {elapsed:.2f}s — exceeds 10s NFR"

    def test_chat_query_type_is_returned(self, client):
        """[SYS-08] Response includes detected query_type."""
        with patch("app.agents.sql_agent.run_readonly_query", return_value=[]):
            response = client.post("/api/aurora/chat", json={
                "message": "Show my balance",
                "account_id": ACCOUNT_ID,
            })

        body = response.json()
        assert body["query_type"] in [
            "account_balance", "transaction_history", "large_transactions",
            "account_summary", "payment_confirmation", "spending_by_category",
            "recent_transactions", "credits_only", "total_spent",
            "account_investigation", "unknown",
        ]

    def test_chat_result_count_is_integer(self, client):
        """[SYS-09] result_count field is always a non-negative integer."""
        with patch("app.agents.sql_agent.run_readonly_query", return_value=[]):
            response = client.post("/api/aurora/chat", json={
                "message": "Show my balance",
                "account_id": ACCOUNT_ID,
            })

        assert isinstance(response.json()["result_count"], int)
        assert response.json()["result_count"] >= 0

    def test_chat_message_max_length_enforced(self, client):
        """[SYS-10] Message longer than 500 chars returns 422."""
        response = client.post("/api/aurora/chat", json={
            "message": "x" * 501,
            "account_id": ACCOUNT_ID,
        })
        assert response.status_code == 422


class TestHealthEndpoint:
    """GET /api/aurora/health"""

    def test_health_returns_200(self, client):
        """[SYS-11] Health endpoint always returns 200."""
        with patch("app.routers.chat.check_connection", return_value=True):
            response = client.get("/api/aurora/health")
        assert response.status_code == 200

    def test_health_returns_correct_fields(self, client):
        """[SYS-12] Health response includes status, db_connected, llm_backend, model."""
        with patch("app.routers.chat.check_connection", return_value=True):
            body = client.get("/api/aurora/health").json()
        assert "status" in body
        assert "db_connected" in body
        assert "llm_backend" in body
        assert "model" in body

    def test_health_status_degraded_when_db_down(self, client):
        """[SYS-13] Health reports 'degraded' when DB is unreachable."""
        with patch("app.routers.chat.check_connection", return_value=False):
            body = client.get("/api/aurora/health").json()
        assert body["status"] == "degraded"
        assert body["db_connected"] is False

    def test_health_status_ok_when_db_connected(self, client):
        """[SYS-14] Health reports 'ok' when DB is reachable."""
        with patch("app.routers.chat.check_connection", return_value=True):
            body = client.get("/api/aurora/health").json()
        assert body["status"] == "ok"
        assert body["db_connected"] is True
