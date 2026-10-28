"""
AuroraAI — Pydantic Request & Response Schemas
"""

from pydantic import BaseModel, Field
from typing import Optional, Any
from datetime import datetime
from enum import Enum


class ChatRequest(BaseModel):
    """Incoming chat message from the user."""
    message: str = Field(..., min_length=1, max_length=500,
                         description="Plain-English query from the user")
    account_id: Optional[str] = Field(None,
                         description="Account UUID to scope the query (optional)")
    customer_id: Optional[str] = Field(None,
                         description="Customer ID for API-based queries")
    session_id: Optional[str] = Field(None, description="Session ID for conversation tracking")

    model_config = {"json_schema_extra": {
        "example": {
            "message": "What is my current balance?",
            "account_id": "550e8400-e29b-41d4-a716-446655440000"
        }
    }}


class ChatResponse(BaseModel):
    """Response returned to the user after the pipeline completes."""
    response:     str           = Field(..., description="Plain-English answer")
    query_type:   str           = Field(..., description="Detected query type")
    result_count: int           = Field(0,   description="Number of records found")
    data:         Optional[Any] = Field(None, description="Raw data (if applicable)")
    generated_at: datetime      = Field(default_factory=datetime.now)


class HealthResponse(BaseModel):
    """Health check response."""
    status:      str  = "ok"
    db_connected: bool = False
    llm_backend:  str  = "ollama"
    model:        str  = ""


class QueryIntent(BaseModel):
    """Output of the Requirements Agent — parsed intent."""
    query_type:   str
    account_id:   Optional[str] = None
    amount_min:   Optional[float] = None
    amount_max:   Optional[float] = None
    date_from:    Optional[str] = None
    date_to:      Optional[str] = None
    limit:        int = 20
    description:  Optional[str] = None
    raw_message:  str = ""


class SQLResult(BaseModel):
    """Output of the SQL Agent — generated query + result."""
    sql:    str
    params: dict
    rows:   list[dict] = []
    error:  Optional[str] = None


class PipelineState(BaseModel):
    """Shared state passed between all three agents in the LangGraph pipeline."""
    message:  str
    account_id: Optional[str] = None
    customer_id: Optional[str] = None
    intent:   Optional[QueryIntent] = None
    sql_result: Optional[SQLResult] = None
    final_response: Optional[str] = None
    error:    Optional[str] = None
