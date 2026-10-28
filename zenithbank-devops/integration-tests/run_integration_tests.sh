#!/usr/bin/env bash
# ============================================================
# ZenithBank — end-to-end integration test
#
# Walks the real user journey through the live API Gateway:
#   register -> verify OTP -> (admin approve) -> login ->
#   dashboard -> create payment -> check status -> ask Aurora
#
# This is NOT a mock — every request hits your actual running
# stack. Run it against docker-compose locally or against your
# EC2 deployment by changing GATEWAY.
#
# Two steps can't be fully automated because they're deliberately
# out-of-band by design, not because the script is incomplete:
#   - OTP arrives by real email (customer-service sends it via
#     Gmail SMTP) — the script pauses and asks you to paste it in.
#   - Admin approval requires an ADMIN_TOKEN for an account with
#     ROLE_ADMIN. If you don't have one yet, the script tells you
#     how to approve manually and lets you re-run from that point.
#
# Requires: bash, curl, jq
#   macOS:   brew install jq
#   Ubuntu:  sudo apt install jq
#
# Usage:
#   GATEWAY=http://localhost:8080 ./run_integration_tests.sh
#   GATEWAY=http://<ec2-public-ip>:8080 ./run_integration_tests.sh
#   ADMIN_TOKEN=<jwt> ./run_integration_tests.sh   # skips manual approval
# ============================================================
set -uo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
ADMIN_TOKEN="${ADMIN_TOKEN:-}"
PASS=0
FAIL=0

green() { printf "\033[32m%s\033[0m\n" "$1"; }
red()   { printf "\033[31m%s\033[0m\n" "$1"; }
info()  { printf "\033[36m%s\033[0m\n" "$1"; }

check() {
  local desc="$1" code="$2" expected="$3"
  if [ "$code" = "$expected" ]; then
    green "  PASS  $desc (HTTP $code)"
    PASS=$((PASS+1))
  else
    red   "  FAIL  $desc (expected $expected, got $code)"
    FAIL=$((FAIL+1))
  fi
}

echo "============================================================"
echo " ZenithBank integration test — target: $GATEWAY"
echo "============================================================"

# ---- 1. Platform health ----
info "\n[1/8] Platform health"
for svc_path in "customers/pending-approval" "aurora/health"; do
  code=$(curl -s -o /dev/null -w "%{http_code}" "$GATEWAY/api/$svc_path")
  check "GET /api/$svc_path reachable" "$code" "200"
done

# ---- 2. Register ----
info "\n[2/8] Register a test customer"
EMAIL="integration.test.$(date +%s)@example.com"
PASSWORD="TestPass123!"
REG_RESP=$(curl -s -X POST "$GATEWAY/api/customers/register" \
  -H "Content-Type: application/json" \
  -d "{
    \"firstName\":\"Integration\",\"lastName\":\"Test\",
    \"email\":\"$EMAIL\",\"phoneNumber\":\"0821234567\",
    \"idNumber\":\"9001015800083\",\"dateOfBirth\":\"1990-01-01\",
    \"addressLine1\":\"1 Test Street\",\"city\":\"Johannesburg\",
    \"province\":\"Gauteng\",\"postalCode\":\"2000\",
    \"password\":\"$PASSWORD\",\"confirmPassword\":\"$PASSWORD\"
  }")
REG_SUCCESS=$(echo "$REG_RESP" | jq -r '.success // false')
# customerId comes straight back from /register — no need to look it
# up separately, and there's no JSON admin-listing endpoint to look
# it up from anyway (only the Thymeleaf /admin/customers HTML view).
CUSTOMER_ID=$(echo "$REG_RESP" | jq -r '.customerId // empty')
if [ "$REG_SUCCESS" = "true" ]; then
  green "  PASS  Registered $EMAIL (customerId=$CUSTOMER_ID)"; PASS=$((PASS+1))
else
  red "  FAIL  Registration failed: $(echo "$REG_RESP" | jq -r '.message // .')"; FAIL=$((FAIL+1))
  echo "Aborting — nothing downstream can run without a registered customer."
  exit 1
fi

# ---- 3. Verify OTP (manual — arrives by real email) ----
info "\n[3/8] Verify OTP"
echo "  Check $EMAIL's inbox (or customer-service logs if using a test SMTP catcher)."
read -rp "  Enter the 6-digit OTP: " OTP_CODE
OTP_RESP=$(curl -s -X POST "$GATEWAY/api/customers/verify-otp" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"otpCode\":\"$OTP_CODE\"}")
OTP_SUCCESS=$(echo "$OTP_RESP" | jq -r '.success // false')
if [ "$OTP_SUCCESS" = "true" ]; then
  green "  PASS  OTP verified"; PASS=$((PASS+1))
else
  red "  FAIL  OTP verification failed: $(echo "$OTP_RESP" | jq -r '.message // .')"; FAIL=$((FAIL+1))
  exit 1
fi

# ---- 4. Admin approval ----
info "\n[4/8] Admin approval"
if [ -n "$ADMIN_TOKEN" ]; then
  APPROVE_RESP=$(curl -s -X POST "$GATEWAY/api/customers/admin/approve/$CUSTOMER_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
  APPROVE_SUCCESS=$(echo "$APPROVE_RESP" | jq -r '.success // false')
  if [ "$APPROVE_SUCCESS" = "true" ]; then
    green "  PASS  Approved via ADMIN_TOKEN"; PASS=$((PASS+1))
  else
    red "  FAIL  Approval failed: $(echo "$APPROVE_RESP" | jq -r '.message // .')"; FAIL=$((FAIL+1))
    exit 1
  fi
else
  echo "  No ADMIN_TOKEN provided — approve manually:"
  echo "  either via the admin dashboard (/admin/customers on customer-service),"
  echo "  or directly in Postgres:"
  echo "    UPDATE customer SET status='ACTIVE' WHERE email='$EMAIL';"
  read -rp "  Press Enter once $EMAIL has been approved..."
fi

# ---- 5. Login ----
info "\n[5/8] Login"
LOGIN_RESP=$(curl -s -X POST "$GATEWAY/api/customers/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
TOKEN=$(echo "$LOGIN_RESP" | jq -r '.token // empty')
if [ -n "$TOKEN" ]; then
  green "  PASS  Logged in, received JWT"; PASS=$((PASS+1))
else
  red "  FAIL  Login failed: $(echo "$LOGIN_RESP" | jq -r '.message // .')"; FAIL=$((FAIL+1))
  exit 1
fi
AUTH_HEADER="Authorization: Bearer $TOKEN"

# ---- 6. Dashboard ----
info "\n[6/8] Banking dashboard"
DASH_RESP=$(curl -s "$GATEWAY/api/banking/dashboard" -H "$AUTH_HEADER")
ACCOUNT_ID=$(echo "$DASH_RESP" | jq -r '.data.accounts[0].id // empty')
if [ -n "$ACCOUNT_ID" ]; then
  green "  PASS  Dashboard loaded, account $ACCOUNT_ID"; PASS=$((PASS+1))
else
  red "  FAIL  No account on dashboard: $(echo "$DASH_RESP" | jq -c '.')"; FAIL=$((FAIL+1))
fi

# ---- 7. Create a payment + check its status ----
info "\n[7/8] Create payment & check status"
if [ -n "$ACCOUNT_ID" ]; then
  PAY_RESP=$(curl -s -X POST "$GATEWAY/api/payments/create" \
    -H "$AUTH_HEADER" -H "Content-Type: application/json" \
    -d "{
      \"fromAccountId\":\"$ACCOUNT_ID\",\"toAccountNo\":\"62812345678\",
      \"branchCode\":\"250655\",\"amount\":50.00,\"reference\":\"integration-test\",
      \"paymentDate\":\"$(date +%F)\",\"isRecurring\":false
    }")
  PAYMENT_ID=$(echo "$PAY_RESP" | jq -r '.data.id // empty')
  if [ -n "$PAYMENT_ID" ]; then
    green "  PASS  Payment created ($PAYMENT_ID)"; PASS=$((PASS+1))
    sleep 2
    STATUS_RESP=$(curl -s "$GATEWAY/api/transactions/$PAYMENT_ID/status" -H "$AUTH_HEADER")
    STATUS_CODE=$(echo "$STATUS_RESP" | jq -r '.data.status // .status // empty')
    if [ -n "$STATUS_CODE" ]; then
      green "  PASS  Status check returned: $STATUS_CODE"; PASS=$((PASS+1))
    else
      red "  FAIL  Status check returned nothing usable: $(echo "$STATUS_RESP" | jq -c '.')"; FAIL=$((FAIL+1))
    fi
  else
    red "  FAIL  Payment creation failed: $(echo "$PAY_RESP" | jq -c '.')"; FAIL=$((FAIL+1))
  fi
else
  red "  SKIP  No account ID from previous step"; FAIL=$((FAIL+1))
fi

# ---- 8. Aurora AI ----
info "\n[8/8] Aurora AI chat"
AURORA_RESP=$(curl -s -X POST "$GATEWAY/api/aurora/chat" \
  -H "$AUTH_HEADER" -H "Content-Type: application/json" \
  -d "{\"message\":\"What is my current balance?\",\"account_id\":\"$ACCOUNT_ID\",\"session_id\":\"integration-test\"}")
AURORA_TEXT=$(echo "$AURORA_RESP" | jq -r '.response // empty')
if [ -n "$AURORA_TEXT" ]; then
  green "  PASS  Aurora responded: ${AURORA_TEXT:0:60}..."; PASS=$((PASS+1))
else
  red "  FAIL  Aurora returned nothing usable: $(echo "$AURORA_RESP" | jq -c '.')"; FAIL=$((FAIL+1))
fi

# ---- Summary ----
echo -e "\n============================================================"
echo " Results: $PASS passed, $FAIL failed"
echo "============================================================"
[ "$FAIL" -eq 0 ]
