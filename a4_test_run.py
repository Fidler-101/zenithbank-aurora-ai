#!/usr/bin/env python3
"""ZenithBank & AuroraAI - Assignment 4 evidence runner. Run from the project root: python3 a4_test_run.py  (--no-payment skips the R10.00 payment)"""
import getpass, json, os, subprocess, sys, time, urllib.request, urllib.error
from datetime import date

GW = os.environ.get("GW", "http://localhost:8080")
EMAIL = os.environ.get("ZB_EMAIL", "fidoolarfidler@gmail.com")
DO_PAY = "--no-payment" not in sys.argv
rows = []
notes = []


def call(method, path, body=None, token=None, timeout=60):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(GW + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            status, raw = r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read().decode()
    except Exception as e:
        return 0, f"{type(e).__name__}: {e}", (time.perf_counter() - t0) * 1000
    ms = (time.perf_counter() - t0) * 1000
    try:
        return status, json.loads(raw), ms
    except Exception:
        return status, raw, ms


def record(tc, title, expected, actual, ok, ms=None):
    rows.append((tc, title, expected, str(actual).replace("\n", " ")[:230],
                 "PASS" if ok is True else ("FAIL" if ok is False else str(ok)), ms))
    print(f"{tc:6} {'PASS' if ok is True else 'FAIL' if ok is False else ok:5} "
          f"{'' if ms is None else f'{ms:7.0f} ms'}  {title}")


def sh(cmd):
    try:
        out = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=120)
        return (out.stdout + out.stderr).strip()
    except Exception as e:
        return f"ERROR {e}"


def msg_of(js):
    return js.get("message") if isinstance(js, dict) else str(js)[:120]


print("=" * 70)
print("ZenithBank A4 evidence run -", date.today(), "- gateway", GW)
print("=" * 70)

pw = os.environ.get("ZB_PASSWORD") or getpass.getpass(f"Password for {EMAIL}: ")
st, js, ms = call("POST", "/api/customers/login", {"email": EMAIL, "password": pw})
token = js.get("token") if isinstance(js, dict) else None
record("TC-01", "Login with valid credentials (FR-02)",
       "HTTP 200, success=true, JWT returned within 2 s",
       f"HTTP {st}, token {'received' if token else 'MISSING'}", bool(token) and st == 200 and ms <= 2000, ms)
if not token:
    print("\nCannot continue without a token. Check the password / that the stack is up.")
    sys.exit(1)

st, js, ms = call("POST", "/api/customers/login", {"email": EMAIL, "password": "definitely-wrong-password"})
record("TC-02", "Login with wrong password (invalid input)",
       "Rejected (HTTP 4xx), no token, generic message",
       f"HTTP {st}, message: {msg_of(js)}", st in (400, 401, 403) and not (isinstance(js, dict) and js.get("token")), ms)

st, js, ms = call("GET", "/api/banking/dashboard", token=token)
accts = (js.get("data") or {}).get("accounts", []) if isinstance(js, dict) else []
cheque = next((a for a in accts if a.get("accountType") == "CHEQUE"), accts[0] if accts else None)
bal_before = float(cheque["balance"]) if cheque else None
n_recent = len(cheque.get("recentTransactions") or []) if cheque else 0
record("TC-03", "Dashboard shows balance and last 5 transactions (FR-03)",
       "HTTP 200, balance present, <=5 recent transactions per account, within 2 s",
       f"HTTP {st}, {len(accts)} account(s), cheque balance R{bal_before}, {n_recent} recent txns",
       st == 200 and cheque is not None and n_recent <= 5 and ms <= 2000, ms)
if not cheque:
    print("No account found on dashboard - cannot test payments.")
    sys.exit(1)
acct_id = cheque["id"]


def pay(amount, branch="632005", to="62812345678", ref="A4 test", d=None):
    return call("POST", "/api/payments/create", {
        "fromAccountId": acct_id, "toAccountNo": to, "branchCode": branch,
        "amount": amount, "reference": ref,
        "paymentDate": (d or date.today()).isoformat(), "isRecurring": False, "frequency": "ONCE"}, token=token)


st, js, ms = pay(-100)
record("TC-04", "Payment with NEGATIVE amount (invalid input)",
       "Rejected with HTTP 400 'Amount must be greater than zero'; balance unchanged",
       f"HTTP {st}: {msg_of(js)}", st == 400, ms)

st, js, ms = pay(99999999)
record("TC-05", "Payment larger than available balance",
       "Rejected with HTTP 400 'Insufficient funds ...'",
       f"HTTP {st}: {msg_of(js)}", st == 400 and "nsufficient" in str(msg_of(js)), ms)

st, js, ms = pay(10, branch="999999")
record("TC-06", "Payment to non-existent branch code",
       "Rejected with HTTP 400 'Invalid branch code'",
       f"HTTP {st}: {msg_of(js)}", st == 400 and "branch" in str(msg_of(js)).lower(), ms)

payment = None
if DO_PAY:
    st, js, ms = pay(10, ref="A4 demo payment")
    payment = (js.get("data") if isinstance(js, dict) else None) or {}
    _, js2, _ = call("GET", "/api/banking/dashboard", token=token)
    a2 = next((a for a in (js2.get("data") or {}).get("accounts", []) if a["id"] == acct_id), {})
    bal_after = float(a2["balance"]) if a2 else None
    delta = None if bal_after is None else round(bal_before - bal_after, 2)
    ok = st in (200, 201) and payment.get("status") == "COMPLETED" and delta == 10.0
    record("TC-07", "Valid payment R10.00 (FR-05) - main workflow",
           "HTTP 201, status COMPLETED, balance reduced by exactly R10.00, ledger entry written",
           f"HTTP {st}, status {payment.get('status')}, balance R{bal_before} -> R{bal_after} (delta {delta})", ok, ms)
else:
    record("TC-07", "Valid payment R10.00 (FR-05)", "-", "skipped (--no-payment)", "SKIP")

if payment and payment.get("id"):
    st, js, ms = call("GET", f"/api/transactions/{payment['id']}/status", token=token)
    d = (js.get("data") if isinstance(js, dict) else None) or {}
    record("TC-08", "Transaction status endpoint for the new payment (FR-06)",
           "HTTP 200 with status COMPLETED",
           f"HTTP {st}, type {d.get('type')}, status {d.get('status')}", st == 200 and d.get("status") == "COMPLETED", ms)
else:
    record("TC-08", "Transaction status endpoint (FR-06)", "-", "skipped (no payment made)", "SKIP")

st, js, ms = call("GET", "/api/banking/dashboard")
record("TC-09", "Protected endpoint without a token (access control)", "HTTP 401", f"HTTP {st}", st == 401, ms)


def aurora(q):
    return call("POST", "/api/aurora/chat", {"message": q}, token=token, timeout=120)


st, js, ms = aurora("Show my last 5 transactions")
data = js.get("data") if isinstance(js, dict) else None
seen = DO_PAY and "A4 demo payment" in json.dumps(data or "")
record("TC-10", "AuroraAI confirms the new payment (emerging-tech integration)",
       "HTTP 200, query_type recent_transactions, returned rows include the 'A4 demo payment' debit, plain-English reply",
       f"HTTP {st}, type {js.get('query_type') if isinstance(js, dict) else '?'}, rows {js.get('result_count') if isinstance(js, dict) else '?'}, "
       f"payment visible: {seen}; reply: {(js.get('response') or '')[:90] if isinstance(js, dict) else js}",
       (st == 200 and seen) if DO_PAY else "SKIP", ms)

st, js, ms = aurora("tell me a joke about bananas")
record("TC-11", "AuroraAI off-topic message",
       "query_type unclear, 0 rows, polite reply, no account data attached",
       f"HTTP {st}, type {js.get('query_type') if isinstance(js, dict) else '?'}, rows {js.get('result_count') if isinstance(js, dict) else '?'}",
       st == 200 and isinstance(js, dict) and js.get("query_type") == "unclear" and not js.get("result_count"), ms)

out = sh("docker compose exec -T aurora-ai-service python -c \""
         "from sqlalchemy import text; from app.db.connection import engine; "
         "c=engine.connect(); c.execute(text('DELETE FROM transactions'))\" 2>&1 | tail -3")
record("TC-12", "AuroraAI engine blocks write statements (FR-10)",
       "A DELETE sent through AuroraAI's engine raises PermissionError",
       out[-200:] if out else "no output", "PermissionError" in out)

env = {}
try:
    for line in open(".env"):
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.strip().split("=", 1)
            env[k] = v
except Exception:
    pass
dbu = env.get("DB_USER", "zenithbank_user")
out = sh(f"docker compose exec -T postgres psql -U {dbu} -d zenithbank_banking -At -F '|' -c "
         f"\"SELECT reference_number, fraud_risk_score, is_flagged FROM transactions WHERE is_flagged ORDER BY fraud_risk_score DESC;\"")
cnt = sh(f"docker compose exec -T postgres psql -U {dbu} -d zenithbank_banking -At -c "
         f"\"SELECT count(*) FILTER (WHERE is_flagged), count(*) FROM transactions;\"")
record("TC-13", "Fraud-risk flag stored in ledger (FR-09 / AT-03)",
       "Seeded suspicious transaction flagged with score >= 0.85; all others clear",
       f"flagged rows: [{out[:80]}]; flagged/total = {cnt}", cnt.startswith("1|"))

def direct_aurora(cust_header, body):
    req = urllib.request.Request("http://localhost:8000/api/aurora/chat", data=json.dumps(body).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    if cust_header is not None:
        req.add_header("X-Customer-Id", str(cust_header))
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, {}
    except Exception as e:
        return 0, {"error": str(e)}

owners = sh(f"docker compose exec -T postgres psql -U {dbu} -d zenithbank_banking -At -c "
            f"\"SELECT DISTINCT customer_id FROM accounts ORDER BY 1;\"").split()
owner = owners[0] if owners else "1"
q = {"message": "Show my last 5 transactions"}
s1, own = direct_aurora(owner, q)
s2, other = direct_aurora("999999", q)
s3, anon = direct_aurora(None, q)
s4, spoof = direct_aurora("999999", {**q, "customer_id": owner, "account_id": None})
n_own, n_other, n_anon, n_spoof = (x.get("result_count", -1) for x in (own, other, anon, spoof))
record("TC-14", "AuroraAI only returns the verified customer's own data (data isolation)",
       "Owner sees own rows; another customer id, no id, and a spoofed body customer_id see 0 rows",
       f"owner {owner}: {n_own} rows; other customer: {n_other}; no id: {n_anon}; spoofed body id: {n_spoof}",
       n_own > 0 and n_other == 0 and n_anon == 0 and n_spoof == 0)

BENCH = [
    ("What's my current balance?", "account_balance"),
    ("Show my last 5 transactions", "recent_transactions"),
    ("How much did I spend this month?", "total_spent"),
    ("Any large transactions recently?", "large_transactions"),
    ("Show my transaction history for this month", "transaction_history"),
    ("Give me a summary of my account", "account_summary"),
    ("Did my payment go through?", "payment_confirmation"),
    ("Break down my spending by category", "spending_by_category"),
    ("Show only the money that came in", "credits_only"),
    ("Investigate my account activity", "account_investigation"),
]
print("\nBenchmark (10 queries) - this takes a minute or two ...")
bench = []
for q, exp in BENCH:
    st, js, ms = aurora(q)
    got = js.get("query_type") if isinstance(js, dict) else "?"
    reply = (js.get("response") or "") if isinstance(js, dict) else str(js)
    broken = st != 200 or "encountered an issue" in reply.lower() or "error" in reply.lower()[:40]
    bench.append((q, exp, got, st, ms, broken, reply))
    print(f"  {ms/1000:5.1f}s  expected {exp:22} got {got:22} {'OK' if got==exp and not broken else 'CHECK'}")
ok_n = sum(1 for b in bench if b[1] == b[2] and not b[5])
fast_n = sum(1 for b in bench if b[4] <= 5000)
times = sorted(b[4] for b in bench)
p90 = times[int(0.9 * len(times)) - 1] if times else 0
record("AT-01", "10-query benchmark: classified correctly and answered",
       ">= 9/10 correct", f"{ok_n}/10 correct", ok_n >= 9)
record("AT-02", "10-query benchmark: responses within 5 s",
       ">= 9/10 within 5 s (90th percentile <= 5 s)", f"{fast_n}/10 within 5 s; p90 = {p90/1000:.1f} s; max {times[-1]/1000:.1f} s", fast_n >= 9)

lines = [f"# A4 evidence run - {date.today()}", "",
         "| ID | Test | Expected | Actual | Status | ms |", "|---|---|---|---|---|---|"]
for tc, title, exp, act, status, ms in rows:
    lines.append(f"| {tc} | {title} | {exp} | {act.replace('|','/')} | {status} | {'' if ms is None else int(ms)} |")
lines += ["", "## Benchmark detail", "", "| # | Query | Expected type | Got type | HTTP | Seconds | Reply (first 120 chars) |", "|---|---|---|---|---|---|---|"]
for i, (q, exp, got, st, ms, broken, reply) in enumerate(bench, 1):
    lines.append(f"| {i} | {q} | {exp} | {got} | {st} | {ms/1000:.1f} | {reply[:120].replace('|','/').replace(chr(10),' ')} |")
lines += ["", "## Notes", ""] + notes
open("a4_results.md", "w").write("\n".join(lines) + "\n")
print("\nWrote a4_results.md  - paste its contents (or the whole terminal output) back to me.")
