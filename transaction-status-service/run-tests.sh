#!/usr/bin/env bash
# ============================================================
# ZenithBank | transaction-status-service — Test Runner
# ============================================================
echo ""
echo "Running tests for: transaction-status-service"
echo "------------------------------------------------------------"

echo ""
echo "[1/3] Unit Tests"
mvn test -Dtest="*UnitTest" -Dspring.profiles.active=test -q

echo ""
echo "[2/3] Integration Tests"
mvn test -Dtest="*IntegrationTest" -Dspring.profiles.active=test -q

echo ""
echo "[3/3] System Tests"
mvn test -Dtest="*SystemTest" -Dspring.profiles.active=test -q

echo ""
echo "All tests complete for transaction-status-service"
