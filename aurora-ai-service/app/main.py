"""
AuroraAI — FastAPI Application Entry Point

Runs on its own port (default: 8000), separate from ZenithBank.
Connects to the same PostgreSQL database as ZenithBank
but only reads from it — never writes.
"""

from fastapi import FastAPI
from app.routers.chat import router as chat_router
from app.config import get_settings
import logging

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)s | %(name)s | %(message)s",
)
logger   = logging.getLogger(__name__)
settings = get_settings()

app = FastAPI(
    title="AuroraAI",
    description=(
        "Natural-language banking assistant. "
        "Ask questions about your accounts and transactions in plain English."
    ),
    version="1.0.0",
    docs_url="/docs",
    redoc_url="/redoc",
)

# NOTE: CORS is intentionally NOT configured here. The browser only ever
# talks to the API Gateway (port 8080) -- the gateway is the single public
# entry point and already sets Access-Control-Allow-Origin correctly for
# every service. AuroraAI previously also set its own CORS headers, and
# with both layers active the browser received two separate
# Access-Control-Allow-Origin values on one response and rejected it
# outright. This service is reached exclusively via the gateway now.

# Register routes under /api/aurora
app.include_router(chat_router, prefix="/api/aurora", tags=["AuroraAI"])


@app.on_event("startup")
async def startup():
    logger.info("AuroraAI starting — LLM backend: %s | model: %s",
                settings.llm_backend,
                settings.ollama_model if settings.llm_backend == "ollama"
                else settings.claude_model)


@app.get("/", include_in_schema=False)
async def root():
    return {
        "service": "AuroraAI",
        "status":  "running",
        "docs":    "/docs",
        "health":  "/api/aurora/health",
    }
