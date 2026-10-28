# ZenithBank & AuroraAI

**ISJ107V Integrated Software Project – Assignment 4 prototype**
Student: Sifiso Nkosi (221587367), Tshwane University of Technology

ZenithBank is a Spring Boot microservices banking back end with a small web front end.
**AuroraAI** is the emerging-technology component: a chatbot built on a three-agent LangGraph
pipeline that turns a plain-English question into a read-only, parameterised SQL query using a
local Llama 3.2 model (Ollama), then explains the result in plain English.

## What the prototype demonstrates

Main workflow: **log in → make a payment → ask AuroraAI to confirm it.**

| Service | Port | Purpose |
|---|---|---|
| frontend (nginx, `zb-microfrontends/`) | 3000 | Login, dashboard, payments, status, Aurora chat |
| api-gateway | 8080 | JWT (RS256) verification, routing, rate limiting |
| customer-service | 8081 | Registration, OTP, admin approval, login |
| banking-service | 7777 | Accounts, ledger, dashboard, fraud-risk scoring |
| payment-service | 8085 | Payments, validation, scheduling |
| transaction-status-service | 8087 | Unified transaction / payment status |
| aurora-ai-service | 8000 | Requirements → SQL → Verification agents |
| eureka-server / redis / postgres | 8761 / – / 5433 (host) | Discovery, rate-limit store, per-service databases |

## Requirements

- Docker (Rancher Desktop or Docker Desktop) with Compose v2, ~6 GB RAM free
- [Ollama](https://ollama.com) with the model pulled: `ollama pull llama3.2`
- Python 3 (only for the evidence runner), `curl`
- Apple-silicon or x86-64 laptop; no GPU needed (responses take a few seconds on CPU)

## Setup

```bash
git clone <this repository>
cd zenith-bank

# 1. Secrets – never committed. Copy the template and fill it in.
cp .env.example .env            # set DB_PASSWORD, REDIS_PASSWORD, SMTP_* (Gmail app password)

# 2. JWT signing keys (RS256) – generate your own.
mkdir -p keys
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out keys/private_key.pem
openssl rsa -pubout -in keys/private_key.pem -out keys/public_key.pem

# 3. Let containers reach Ollama on the host (macOS; repeat after a reboot, then restart Ollama)
launchctl setenv OLLAMA_HOST "0.0.0.0"

# 4. Start everything (first build takes several minutes)
docker compose up -d --build
```

Wait until `curl http://localhost:8080/api/aurora/health` returns `"status":"ok"`
(containers show *Started* before the Java services are ready – give it ~1–2 minutes).

Open **http://localhost:3000**.

## Test credentials / sample data

No credentials are stored in this repository. The demonstration account and its password are
given in the submitted report (section 1.3). Sample ledger data (accounts and 8 transactions,
including one deliberately suspicious high-risk transaction) is created by the Flyway
migrations (`banking-service/src/main/resources/db/migration/V1__…`, `V2__…`).
New customers register through the UI, verify an e-mailed OTP, and must be approved by an
administrator before they can log in.

## Running the tests

```bash
# Unit / integration tests per service
(cd banking-service && ./run-tests.sh)
(cd payment-service && ./run-tests.sh)
(cd aurora-ai-service && ./run-tests.sh)

# Live end-to-end evidence run against the running stack (makes one R10.00 payment)
python3 a4_test_run.py            # add --no-payment to skip the payment
```

## Documentation

UML sources (PlantUML) are in `docs/uml/`; the report figures were generated from them.

## Known limitations (honest list)

- AuroraAI classified all 10 benchmark queries correctly in the final run (the first run scored 8/10;
  two defects were fixed), but only 4 of 10 answered within 5 s on a CPU-only laptop (an earlier run
  scored 9/10). The cause is two sequential local LLM calls per query. A Claude API backend exists
  (`LLM_BACKEND=claude`) as the planned mitigation.
- AuroraAI queries are scoped to the customer id the API gateway verified (`X-Customer-Id`). Service
  ports are still published on the host and internal endpoints trust the gateway, so a caller who
  bypasses the gateway could forge that header; the planned fix is to publish only the gateway.
- Fraud-risk scoring is a transparent drain-ratio heuristic, validated on only 8 seeded
  transactions – not a statistically meaningful benchmark.
- The LLM's prose can misstate figures; the exact database rows are returned alongside every answer.
- Local development setup only: not hardened for production (see `zenithbank-devops/`).

## AI-tool use

AI assistance (Claude) was used for code review, debugging and testing, and to polish the documentation
(README, UML diagram source, report wording). The architecture and design are the student's own; all
tests were run and verified by the student.
