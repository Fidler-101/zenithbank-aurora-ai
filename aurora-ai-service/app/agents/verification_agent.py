"""
AuroraAI — Verification Agent (Agent 3)

Receives the SQL query result from the SQL Agent and:
  1. Validates the result is non-empty and meaningful
  2. Formats it into a readable plain-English response
  3. Handles error cases gracefully without exposing SQL or stack traces

Uses the LLM for natural language formatting when results exist.
Falls back to a template-based formatter when the LLM is unavailable.
"""

import logging
from app.models.schemas import PipelineState
from app.config import get_settings
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.output_parsers import StrOutputParser

logger = logging.getLogger(__name__)
settings = get_settings()

SYSTEM_PROMPT = """You are a friendly South African banking assistant.
Format the database query result into a clear, helpful plain-English response.

Rules:
- Use Rands (R) for all currency amounts
- Be concise — no more than 150 words
- If the result is a list of transactions, summarise the key ones
- Never mention SQL, databases, or technical details
- Address the user as "you" / "your"
- End with a brief helpful note if appropriate"""

USER_PROMPT = """Query type: {query_type}
Original question: {message}
Result ({count} record(s)):
{data}

Write a plain-English response:"""


def run_verification_agent(state: PipelineState) -> PipelineState:
    """
    Verification Agent entry point.
    Validates the SQL result and formats the final response.
    """
    logger.info("Verification Agent — validating result")

    # Handle upstream errors
    if state.error:
        state.final_response = (
            "I'm sorry, I wasn't able to process your request. "
            "Please try rephrasing your question or contact support."
        )
        return state

    if state.sql_result is None:
        state.final_response = "Something went wrong while retrieving your data. Please try again."
        return state

    # Handle SQL execution errors
    if state.sql_result.error:
        logger.error("Verification Agent — SQL error: %s", state.sql_result.error)
        state.final_response = (
            "I encountered an issue retrieving that information. "
            "Please try again or rephrase your question."
        )
        return state

    rows = state.sql_result.rows
    count = len(rows)

    # Handle empty results
    if count == 0:
        state.final_response = _empty_result_message(state.intent.query_type if state.intent else "unknown")
        return state

    # Format the response
    try:
        state.final_response = _format_with_llm(state, rows, count)
    except Exception as e:
        logger.warning("Verification Agent LLM formatting failed (%s) — using template", e)
        state.final_response = _template_format(state, rows, count)

    return state


def _format_with_llm(state: PipelineState, rows: list, count: int) -> str:
    """Use the LLM to produce a natural-language response."""
    from app.agents.requirements_agent import _build_llm

    # Summarise data so we don't exceed token limits
    data_preview = _summarise_rows(rows, max_rows=10)

    llm    = _build_llm()
    prompt = ChatPromptTemplate.from_messages([
        ("system", SYSTEM_PROMPT),
        ("human",  USER_PROMPT),
    ])
    chain = prompt | llm | StrOutputParser()

    return chain.invoke({
        "query_type": state.intent.query_type if state.intent else "general",
        "message":    state.message,
        "count":      count,
        "data":       data_preview,
    })


def _template_format(state: PipelineState, rows: list, count: int) -> str:
    """Template-based fallback formatter — handles all 10 query types."""
    query_type = state.intent.query_type if state.intent else "transaction_history"

    match query_type:

        case "account_balance":
            row = rows[0]
            return (
                f"Your {row.get('account_type','account')} account "
                f"({row.get('account_number','')}) has a current balance of "
                f"R{float(row.get('balance', 0)):,.2f}. "
                f"Your available balance is R{float(row.get('available_balance', 0)):,.2f}."
            )

        case "account_summary":
            row = rows[0]
            return (
                f"Account {row.get('account_number','')} summary: "
                f"Balance R{float(row.get('balance', 0)):,.2f}. "
                f"In the selected period: {row.get('transaction_count', 0)} transactions — "
                f"R{float(row.get('total_credits', 0)):,.2f} in, "
                f"R{float(row.get('total_debits', 0)):,.2f} out."
            )

        case "total_spent":
            row = rows[0]
            return (
                f"You spent a total of R{float(row.get('total_spent', 0)):,.2f} "
                f"across {row.get('transaction_count', 0)} transaction(s) in the selected period."
            )

        case "spending_by_category":
            lines = [f"Your spending by category:"]
            for row in rows[:5]:
                lines.append(
                    f"  • {row.get('category','?')}: R{float(row.get('total_amount', 0)):,.2f} "
                    f"({row.get('count', 0)} transactions)"
                )
            return "\n".join(lines)

        case "large_transactions":
            lines = [f"Found {count} transaction(s) above the threshold:"]
            for row in rows[:5]:
                sign = "+" if row.get("type") in ("CREDIT","TRANSFER_IN") else "-"
                lines.append(
                    f"  • R{float(row.get('amount', 0)):,.2f} — "
                    f"{row.get('description','')[:50]} "
                    f"({str(row.get('initiated_at',''))[:10]})"
                )
            return "\n".join(lines)

        case "payment_confirmation":
            if not rows:
                return "I couldn't find a matching payment. It may still be processing or the details may differ."
            row = rows[0]
            status = row.get("status", "UNKNOWN")
            return (
                f"Payment of R{float(row.get('amount', 0)):,.2f} to account "
                f"{row.get('to_account_no','')} — status: {status}. "
                + ("Payment completed successfully." if status == "COMPLETED"
                   else "Payment is still being processed.")
            )

        case "credits_only":
            total = sum(float(r.get("amount", 0)) for r in rows)
            lines = [f"Found {count} incoming payment(s) totalling R{total:,.2f}:"]
            for row in rows[:5]:
                lines.append(
                    f"  • R{float(row.get('amount', 0)):,.2f} — "
                    f"{row.get('description','')[:50]} "
                    f"({str(row.get('initiated_at',''))[:10]})"
                )
            return "\n".join(lines)

        case _:  # transaction_history, recent_transactions, account_investigation
            lines = [f"Here are {min(count, 5)} of your {count} transaction(s):"]
            for row in rows[:5]:
                t = row.get("type","")
                sign = "+" if t in ("CREDIT","TRANSFER_IN","INTEREST") else "-"
                lines.append(
                    f"  • {sign}R{float(row.get('amount', 0)):,.2f} — "
                    f"{row.get('description','')[:50]} "
                    f"({str(row.get('initiated_at',''))[:10]})"
                )
            if count > 5:
                lines.append(f"  ... and {count - 5} more.")
            return "\n".join(lines)


def _empty_result_message(query_type: str) -> str:
    """Friendly message when no records match."""
    messages = {
        "account_balance":      "I couldn't find an active account. Please check your account details.",
        "transaction_history":  "No transactions found for the selected period.",
        "large_transactions":   "No transactions above the specified amount were found.",
        "account_summary":      "No account activity found for the selected period.",
        "payment_confirmation": "No matching payment found. It may still be pending or the details may differ.",
        "spending_by_category": "No spending found for the selected category and period.",
        "recent_transactions":  "No transactions found on your account yet.",
        "credits_only":         "No incoming payments found for the selected period.",
        "total_spent":          "No spending transactions found for the selected period.",
        "account_investigation":"No transactions found matching those criteria.",
        "greeting":             "Hello! I can help you check your balance, view recent transactions, see your spending, or confirm a payment. What would you like to know?",
        "unclear":              "I'm not sure I understood that. I can help with things like your account balance, recent transactions, spending summaries, or payment confirmations -- could you rephrase your question?",
    }
    return messages.get(query_type, "No results found for your query.")


def _summarise_rows(rows: list, max_rows: int = 10) -> str:
    """Convert rows to a compact string for the LLM prompt."""
    lines = []
    for i, row in enumerate(rows[:max_rows]):
        # Convert non-serialisable types to strings
        clean = {k: str(v) if not isinstance(v, (int, float, str, bool, type(None))) else v
                 for k, v in row.items()}
        lines.append(str(clean))
    if len(rows) > max_rows:
        lines.append(f"... ({len(rows) - max_rows} more rows not shown)")
    return "\n".join(lines)
