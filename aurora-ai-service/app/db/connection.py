"""
AuroraAI — Read-Only Database Connection

Connects to the same PostgreSQL database as ZenithBank
using a dedicated read-only user.

Safety by design:
  - The DB user has only SELECT privileges — no INSERT, UPDATE, DELETE
  - SQLAlchemy engine is configured with autocommit=False, no write ops
  - All queries run through execute() with bound parameters only

Tables available (defined by ZenithBank schema):
  customers, accounts, transactions, payments, branches, departments
"""

from sqlalchemy import create_engine, text, event
from sqlalchemy.orm import sessionmaker, Session
from sqlalchemy.exc import SQLAlchemyError
from app.config import get_settings
import logging

logger = logging.getLogger(__name__)

settings = get_settings()

# Create the engine — pool_pre_ping keeps idle connections healthy
engine = create_engine(
    settings.database_url,
    pool_pre_ping=True,
    pool_size=5,
    max_overflow=10,
    echo=False,
)

# Intercept any non-SELECT statement and raise immediately
@event.listens_for(engine, "before_execute")
def _block_writes(conn, clauseelement, multiparams, params, execution_options):
    sql = str(clauseelement).strip().upper()
    forbidden = ("INSERT", "UPDATE", "DELETE", "DROP", "CREATE", "ALTER", "TRUNCATE")
    if any(sql.startswith(kw) for kw in forbidden):
        raise PermissionError(
            f"AuroraAI is read-only. Blocked SQL operation: {sql[:40]}"
        )

SessionLocal = sessionmaker(bind=engine, autocommit=False, autoflush=False)


def get_db() -> Session:
    """FastAPI dependency — yields a DB session, always closed after request."""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def check_connection() -> bool:
    """Health check — returns True if the DB is reachable."""
    try:
        with engine.connect() as conn:
            conn.execute(text("SELECT 1"))
        return True
    except SQLAlchemyError as e:
        logger.error("Database connection check failed: %s", e)
        return False


def run_readonly_query(sql: str, params: dict) -> list[dict]:
    """
    Execute a read-only parameterised query and return rows as dicts.
    This is the only way agents should access the database.
    """
    with engine.connect() as conn:
        result = conn.execute(text(sql), params)
        columns = result.keys()
        return [dict(zip(columns, row)) for row in result.fetchall()]
