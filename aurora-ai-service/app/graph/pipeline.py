from __future__ import annotations
"""
AuroraAI — LangGraph Pipeline

Wires the three agents into a directed state graph:

  START
    │
    ▼
  requirements_agent   ← Agent 1: parse intent from plain English
    │
    ▼
  sql_agent            ← Agent 2: build + execute parameterised SELECT query
    │
    ▼
  verification_agent   ← Agent 3: validate result + format plain-English response
    │
    ▼
  END

State flows through each agent as a PipelineState object.
Each agent receives the full state, updates its own field, and returns it.
If any agent sets state.error, subsequent agents handle it gracefully.
"""

from langgraph.graph import StateGraph, END
from app.models.schemas import PipelineState
from app.agents.requirements_agent import run_requirements_agent
from app.agents.sql_agent import run_sql_agent
from app.agents.verification_agent import run_verification_agent
import logging

logger = logging.getLogger(__name__)


def _requirements_node(state: dict) -> dict:
    """LangGraph node wrapper for the Requirements Agent."""
    pipeline_state = PipelineState(**state)
    updated = run_requirements_agent(pipeline_state)
    return updated.model_dump()


def _sql_node(state: dict) -> dict:
    """LangGraph node wrapper for the SQL Agent."""
    pipeline_state = PipelineState(**state)
    updated = run_sql_agent(pipeline_state)
    return updated.model_dump()


def _verification_node(state: dict) -> dict:
    """LangGraph node wrapper for the Verification Agent."""
    pipeline_state = PipelineState(**state)
    updated = run_verification_agent(pipeline_state)
    return updated.model_dump()


def build_pipeline() -> StateGraph:
    """
    Build and compile the LangGraph pipeline.
    Called once at application startup.
    """
    graph = StateGraph(dict)

    # Register agent nodes
    graph.add_node("requirements_agent", _requirements_node)
    graph.add_node("sql_agent",          _sql_node)
    graph.add_node("verification_agent", _verification_node)

    # Define the flow: linear — no branching needed
    graph.set_entry_point("requirements_agent")
    graph.add_edge("requirements_agent", "sql_agent")
    graph.add_edge("sql_agent",          "verification_agent")
    graph.add_edge("verification_agent", END)

    return graph.compile()


def run_pipeline(message: str, account_id: str | None = None, customer_id: str | None = None) -> PipelineState:
    """
    Entry point called by the chat router.
    Runs the full three-agent pipeline and returns the final PipelineState.
    """
    logger.info("Pipeline starting — message: '%s'", message[:80])

    pipeline = build_pipeline()

    initial_state = PipelineState(
        message=message,
        account_id=account_id,
        customer_id=customer_id,
    ).model_dump()

    result = pipeline.invoke(initial_state)

    final = PipelineState(**result)
    logger.info("Pipeline complete — response length: %d chars",
                len(final.final_response or ""))
    return final
