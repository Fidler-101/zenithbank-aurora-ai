# ZenithBank — DevOps package

Everything needed to containerize, test, and deploy the system, built directly against your real ports, env vars, and service names (checked against your actual `application.properties` files, not assumed).

```
zb-devops/
├── dockerfiles/                    Dockerfile per service — copy each into that service's own folder
├── docker-compose.yml              Full local stack, builds from source
├── docker-compose.prod.yml         Same stack, pulls pre-built images from GHCR (used by CI/CD)
├── .env.example                    Copy to .env, fill in real secrets, never commit .env
├── init-db/                        Creates the 3 per-service Postgres databases on first boot
├── k8s/                            Kubernetes manifests (namespace, config, all 8 services, ingress)
├── ci-cd/.github/workflows/        GitHub Actions: test -> build -> push -> deploy to EC2
├── integration-tests/              Real end-to-end test script against a live stack
└── AWS_EC2_DEPLOYMENT_GUIDE.md     First-time walkthrough, start to finish
```

## Quickest path to seeing it running: Docker Compose, locally

1. Copy each `dockerfiles/Dockerfile.<service>` into that service's folder as `Dockerfile`.
2. Copy `docker-compose.yml`, `.env.example` → `.env`, and `init-db/` to your project root, alongside `zb-microfrontends/`.
3. Generate a JWT keypair (don't reuse the one committed in the repo):
   ```bash
   mkdir keys
   openssl genrsa -out keys/private_key.pem 2048
   openssl rsa -in keys/private_key.pem -pubout -out keys/public_key.pem
   ```
4. Fill in `.env` with real values.
5. ```bash
   docker compose --env-file .env up -d --build
   docker compose ps   # wait for everything to show "healthy"
   ```
6. Frontend: `http://localhost:3000`. Gateway: `http://localhost:8080/api/...`.

## Then: run the integration tests

```bash
./integration-tests/run_integration_tests.sh
```
Walks the real journey — register, verify OTP (you'll paste the code from your email), admin-approve, log in, load the dashboard, create a payment, check its status, and ask Aurora a question — against your actual running containers. Two steps need a human because they're deliberately out-of-band (email delivery, admin approval) — the script tells you exactly what to do at each one.

## Then: Kubernetes (optional — only if your module wants a K8s demo specifically)

Compose is enough to prove containerization and orchestration for most rubrics. If you specifically need to show Kubernetes:

```bash
kubectl apply -f k8s/00-namespace.yaml
kubectl create secret generic zenithbank-secrets -n zenithbank \
  --from-literal=DB_USER=zenithbank_user \
  --from-literal=DB_PASSWORD='<real password>' \
  --from-literal=REDIS_PASSWORD='<real password>' \
  --from-literal=SMTP_EMAIL='<gmail address>' \
  --from-literal=SMTP_APP_PASSWORD='<gmail app password>' \
  --from-literal=ANTHROPIC_API_KEY='<key or leave blank>'
kubectl create secret generic zenithbank-jwt-keys -n zenithbank \
  --from-file=private_key.pem=./keys/private_key.pem \
  --from-file=public_key.pem=./keys/public_key.pem
kubectl apply -f k8s/
```
The Deployments reference `${REGISTRY}/zenithbank-<service>:${TAG}` — replace those placeholders with real image references (e.g. after the CI/CD pipeline has pushed to GHCR) before applying, or run `envsubst < k8s/06-backend-services.yaml | kubectl apply -f -` per file. This needs an actual cluster — `minikube` or `kind` locally, or EKS if you want it on AWS. I didn't build an EKS-specific guide since the EC2 + Docker Compose path is the more realistic target for a solo project on a deadline; ask if you want the EKS version too.

## Then: CI/CD and EC2

Read `AWS_EC2_DEPLOYMENT_GUIDE.md` start to finish before doing anything — it covers instance sizing (this stack doesn't fit in the free tier), security group setup, and the full manual deploy, then how to wire the GitHub Actions pipeline in `ci-cd/` on top of it for automatic redeploys on push.

## One frontend fix that mattered

While building this, I caught and fixed a real bug in `zb-microfrontends/shared/zb-common.js`: the API base URL was hardcoded to `http://localhost:8080`, which only ever means "whoever's browser is loading this page." Deployed to EC2 unchanged, every API call from a visitor's browser would have silently tried to reach port 8080 on *their own laptop* instead of your server. It now derives from `window.location.hostname`, so the same build works locally, on EC2, or anywhere else without editing code per environment.

## What's genuinely tested vs. what needs you to run it

I validated every file's syntax (YAML parses, bash passes `bash -n`, JS passes `node --check`) and cross-checked every port, env var, and endpoint against your actual source code rather than assuming defaults. What I could not do from this sandbox: actually run `docker build`, start containers, or execute the integration tests against a live stack — no Docker daemon, no network access, no AWS credentials here. Everything is ready to run; the first real execution has to happen on your machine or the EC2 box. If something fails on your end, send me the exact error output and I'll debug from there.
