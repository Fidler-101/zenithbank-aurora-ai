#!/usr/bin/env bash
# ============================================================
# ZenithBank | customer-service — Test Runner
# ============================================================
echo ""
echo "Running tests for: customer-service"
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
echo "All tests complete for customer-service"
