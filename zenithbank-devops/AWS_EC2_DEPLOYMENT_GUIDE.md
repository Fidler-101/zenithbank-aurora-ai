# Deploying ZenithBank to AWS EC2 — a first-timer's guide

This walks through everything from "I have an AWS account" to "the site is live at a public IP." Read the whole thing once before typing anything — a couple of steps (instance size, security groups) are easy to get wrong in ways that cost you money or leave the box wide open.

## Before you start: what this is going to cost

This stack is **9 containers**: Postgres, Redis, Eureka, the gateway, 4 Spring Boot services, Aurora, and the frontend. That does not fit in AWS's free-tier `t2.micro`/`t3.micro` (1 GB RAM) — it will crash or swap constantly. You need at least:

- **t3.medium** (2 vCPU, 4 GB RAM) — workable for a demo, might be tight
- **t3.large** (2 vCPU, 8 GB RAM) — comfortable

Neither is free. At the time of writing, a `t3.large` in most regions is roughly **$0.08/hour** (~$60/month if left running 24/7). **Stop the instance** (not terminate — stop) when you're not actively demoing it; you're only billed for storage while stopped, which is a few cents. Don't leave it running over a weekend by accident.

---

## Step 1 — Launch the instance

1. AWS Console → **EC2** → **Launch instance**.
2. **Name**: `zenithbank-demo`.
3. **AMI**: Ubuntu Server 22.04 LTS (search "Ubuntu" in the AMI picker — pick the free-tier-eligible one, the cost is about instance size, not the AMI).
4. **Instance type**: `t3.large` (or `t3.medium` if you want to save money and are only running a subset of services for the demo).
5. **Key pair**: create a new one, name it `zenithbank-key`, download the `.pem` file. **You cannot re-download this later** — if you lose it, you lose SSH access to the instance. Keep it somewhere safe.
6. **Network settings** → click **Edit** and set up the security group by hand (the default is too permissive):
   | Type | Port | Source | Why |
   |---|---|---|---|
   | SSH | 22 | **My IP** (not 0.0.0.0/0) | So only you can SSH in |
   | Custom TCP | 3000 | 0.0.0.0/0 | Frontend |
   | Custom TCP | 8080 | 0.0.0.0/0 | API Gateway |

   Everything else (Postgres 5432, Redis 6379, Eureka 8761, the individual services' own ports) should **not** be open to the internet — they're only reachable by other containers on the same Docker network. If you followed the earlier security review, you'll recognize this as the same "don't let people reach services directly, bypassing the gateway" principle — the EC2 security group is what actually enforces it once this is on a real network instead of your laptop.
7. **Storage**: bump this to 30 GB (the default 8 GB will fill up fast once you've pulled 9 images plus Postgres data).
8. **Launch instance**.

## Step 2 — Connect

```bash
chmod 400 ~/Downloads/zenithbank-key.pem
ssh -i ~/Downloads/zenithbank-key.pem ubuntu@<your-instance-public-ip>
```

Find the public IP on the instance's detail page in the EC2 console (it changes if you stop/start the instance, unless you allocate an **Elastic IP** — worth doing if you'll be demoing over several sessions, since otherwise you have to update your frontend's target and your security group's "My IP" each time).

## Step 3 — Install Docker

```bash
sudo apt update && sudo apt upgrade -y
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
newgrp docker   # or log out and back in
docker --version
docker compose version
```

## Step 4 — Get the code onto the instance

Simplest for a first deployment — clone your repo:
```bash
git clone https://github.com/<your-username>/<your-repo>.git zenithbank
cd zenithbank
```

If your repo isn't public yet, either make it temporarily private-with-deploy-key, or `scp` a zip up instead:
```bash
# from your own machine
scp -i ~/Downloads/zenithbank-key.pem your-project.zip ubuntu@<ip>:~/
# then on the instance
unzip your-project.zip -d zenithbank && cd zenithbank
```

## Step 5 — Lay out the files this guide expects

From the `devops` package I gave you:
```bash
# Dockerfiles — one per service, copied to that service's own folder
cp dockerfiles/Dockerfile.eureka-server eureka-server/Dockerfile
cp dockerfiles/Dockerfile.api-gateway api-gateway/Dockerfile
cp dockerfiles/Dockerfile.customer-service customer-service/Dockerfile
cp dockerfiles/Dockerfile.banking-service banking-service/Dockerfile
cp dockerfiles/Dockerfile.payment-service payment-service/Dockerfile
cp dockerfiles/Dockerfile.transaction-status-service transaction-status-service/Dockerfile
# aurora-ai-service/Dockerfile already exists in your project

# Compose files + env template + db init script go at the repo root
cp docker-compose.yml .
cp docker-compose.prod.yml .
cp .env.example .env
cp -r init-db .

# Frontend
cp -r zb-microfrontends .
```

## Step 6 — Generate the JWT keypair

Don't reuse the private key that was committed in the repo (flagged in the earlier security review) — generate a fresh one on the server:
```bash
mkdir -p keys
openssl genrsa -out keys/private_key.pem 2048
openssl rsa -in keys/private_key.pem -pubout -out keys/public_key.pem
```
`customer-service` and `api-gateway` both read from this `keys/` folder (already wired up in `docker-compose.yml`).

## Step 7 — Fill in your secrets

```bash
nano .env
```
Set real values for `DB_PASSWORD`, `REDIS_PASSWORD`, `SMTP_EMAIL`/`SMTP_APP_PASSWORD` (a Gmail [App Password](https://myaccount.google.com/apppasswords), not your real Gmail password), and `ANTHROPIC_API_KEY` if you're using the Claude backend for Aurora. Leave `LLM_BACKEND=ollama` and skip the Anthropic key if you're running a local Ollama model instead — note that Ollama itself isn't in this Docker stack, so either install it directly on the EC2 box (`curl -fsSL https://ollama.com/install.sh | sh`) or switch `LLM_BACKEND` to `claude` for the demo, since running a local LLM well on a `t3.large` is a stretch.

## Step 8 — Build and start everything

For a first deploy, build directly from source on the box (slower, but doesn't need GHCR set up yet):
```bash
docker compose --env-file .env up -d --build
```
This will take a while the first time — 6 separate Maven builds plus image downloads. Watch progress:
```bash
docker compose ps
docker compose logs -f api-gateway
```
Wait until `docker compose ps` shows everything as `healthy` before testing — Eureka and the gateway both have startup grace periods baked into their healthchecks.

## Step 9 — Verify

From your own machine (not the EC2 box):
```bash
curl http://<instance-ip>:8080/api/aurora/health
```
Then open `http://<instance-ip>:3000` in a browser — that's the ZenithBank frontend, now talking to the gateway on the same host. This is exactly why the frontend's `API_BASE` was changed to derive from `window.location.hostname` instead of a hardcoded `localhost` — without that fix, the frontend would have tried to reach port 8080 on *your laptop*, not the server, and every request would have silently failed.

## Step 10 — Run the integration tests against the real deployment

```bash
GATEWAY=http://<instance-ip>:8080 ./integration-tests/run_integration_tests.sh
```

## Step 11 (optional) — Wire up the CI/CD pipeline to deploy here automatically

Once step 8 works manually, switch to the pipeline in `ci-cd/.github/workflows/ci-cd.yml`, which builds images in GitHub Actions and pushes to GHCR instead of building on the EC2 box every time (much faster, and the box doesn't need Maven/JDK installed just to redeploy).

1. On the EC2 box, switch to the pre-built-image compose file:
   ```bash
   mv docker-compose.prod.yml docker-compose.yml   # back up the build-from-source one first
   echo "GHCR_OWNER=<your-github-username>" >> .env
   echo "TAG=latest" >> .env
   ```
2. In your GitHub repo → **Settings → Secrets and variables → Actions**, add:
   - `EC2_HOST` — the instance's public IP
   - `EC2_USER` — `ubuntu`
   - `EC2_SSH_KEY` — the full contents of `zenithbank-key.pem`
3. Push to `main`. The pipeline tests every service, builds all 8 images, pushes them to `ghcr.io`, then SSHs into the box and runs `docker compose pull && docker compose up -d`.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `docker compose ps` shows a service restarting in a loop | `docker compose logs <service>` — almost always a missing/wrong env var, or Postgres not ready yet (check `depends_on` ran its healthcheck) |
| Frontend loads but every action fails with "Can't reach the API Gateway" | Port 8080 not open in the security group, or you're testing from a network the security group doesn't allow |
| `docker compose up --build` runs out of memory | You're on a `t3.medium` or smaller — either upsize temporarily for the build, or build images in CI (step 11) instead of on the box |
| Can't SSH in at all | Security group's SSH rule is locked to an IP that changed (common on home Wi-Fi) — edit the rule to your current IP |

## What I could and couldn't do for you here

I built and syntax-checked every file above (Dockerfiles, `docker-compose.yml`, the Kubernetes manifests, the CI/CD pipeline, the integration test script), and fixed a real bug in the frontend (the hardcoded `localhost` API URL) that would have broken silently the moment you deployed it anywhere but your own machine. What I can't do from this sandbox is actually launch an EC2 instance, run `docker compose up`, or execute the integration tests against a live stack — I have no AWS credentials, no Docker daemon, and no network access here. Everything above is ready to run; the "actually running it" part has to happen on your side. If something breaks at a specific step, paste me the exact error and I'll debug it with you.
