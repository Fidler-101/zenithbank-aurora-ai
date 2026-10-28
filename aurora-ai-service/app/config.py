"""
AuroraAI Configuration
Loads from .env file or environment variables.
"""

from pydantic_settings import BaseSettings
from functools import lru_cache


class Settings(BaseSettings):
    # Database — read-only connection to the shared ZenithBank DB
    database_url: str = "postgresql://zenithbank_user:123@localhost:5432/zenithbank_banking"

    # LLM — Ollama local model (primary)
    ollama_base_url: str = "http://localhost:11434"
    ollama_model: str = "llama3.2"

    # LLM — Claude API (fallback when Ollama unavailable)
    anthropic_api_key: str = ""
    claude_model: str = "claude-sonnet-4-6"

    # Which backend to use: "ollama" | "claude"
    llm_backend: str = "ollama"

    # App
    app_port: int = 8000
    debug: bool = False

    # Response time target (NFR-P-03): 5 seconds for 90% of queries
    llm_timeout_seconds: int = 10

    # ZenithBank Microservice URLs (API-first approach — industry standard)
    banking_service_url: str = "http://localhost:7777"
    payment_service_url: str = "http://localhost:8085"
    customer_service_url: str = "http://localhost:8081"
    transaction_status_url: str = "http://localhost:8087"

    # AuroraAI service account token (injected at runtime via login)
    aurora_service_token: str = ""

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"


@lru_cache()
def get_settings() -> Settings:
    return Settings()
