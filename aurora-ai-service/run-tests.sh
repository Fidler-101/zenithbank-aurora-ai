#!/usr/bin/env bash
# ============================================================
# ZenithBank | aurora-ai-service — Test Runner
# ============================================================
echo ""
echo "Running tests for: aurora-ai-service"
echo "------------------------------------------------------------"

echo ""
echo "[1/4] Unit Tests"
pytest tests/test_unit_agents.py -v --tb=short -m unit || true

echo ""
echo "[2/4] Integration Tests"
pytest tests/test_integration_pipeline.py -v --tb=short -m integration || true

echo ""
echo "[3/4] System Tests"
pytest tests/test_system_aurora.py -v --tb=short -m system || true

echo ""
echo "[4/4] Acceptance Tests (Spec Section 10.3)"
pytest tests/test_acceptance.py -v --tb=short -m acceptance || true

echo ""
echo "All tests complete for aurora-ai-service"
