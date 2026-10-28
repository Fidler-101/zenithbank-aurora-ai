from __future__ import annotations
"""
AuroraAI — Requirements Agent (Agent 1)

Reads the user's plain-English message and extracts intent.
Returns a structured QueryIntent object that the SQL Agent
will use to build a safe, parameterised SQL query.

Supports the 10 query types defined in FR-AI-02:
  1.  account_balance       — "What is my current balance?"
  2.  transaction_history   — "Show my transactions from 1 Jan to 31 Jan"
  3.  large_transactions    — "Show all transactions above R5000 this month"
  4.  account_summary       — "Give me a summary of my account activity"
  5.  payment_confirmation  — "Did my payment to account 1001 go through?"
  6.  spending_by_category  — "How much did I spend on groceries last month?"
  7.  recent_transactions   — "What are my last 10 transactions?"
  8.  credits_only          — "Show me all deposits this week"
  9.  total_spent           — "How much did I spend in February?"
  10. account_investigation — "List all transactions for account 1001 flagged as high value"
"""

import json
import logging
import re
from datetime import datetime, date, timedelta
from app.models.schemas import QueryIntent, PipelineState
from app.config import get_settings
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.output_parsers import StrOutputParser

logger = logging.getLogger(__name__)
settings = get_settings()

SYSTEM_PROMPT = """You are a banking query parser. Extract the intent from the user's message.

Return ONLY valid JSON with these fields (no markdown, no explanation):
{{
  "query_type": one of [account_balance, transaction_history, large_transactions, account_summary, payment_confirmation, spending_by_category, recent_transactions, credits_only, total_spent, account_investigation, unclear],
  "account_id": string or null,
  "amount_min": number or null,
  "amount_max": number or null,
  "date_from": "YYYY-MM-DD" or null,
  "date_to": "YYYY-MM-DD" or null,
  "limit": number (default 20),
  "description": string or null
}}

Use one of the exact query_type values listed above -- never invent a new one.
IMPORTANT: check first whether the message is actually about banking at all
(balance, transactions, spending, payments). If it is not -- e.g. small talk,
a joke, a question about you the assistant, or anything with no financial
content -- you MUST return "unclear", even if you could technically force
an answer some other way. Do not let an incidental word (e.g. "account",
"money") pull an off-topic message into a banking category.
Example: "tell me a joke about bananas" -> unclear (NOT transaction_history,
even though transactions exist to show).

Guidance for commonly confused cases (once you've confirmed it IS a banking question):
- "last N transactions" / "recent transactions" -> recent_transactions (NOT transaction_history)
- "transactions from X to Y" (an explicit date range)         -> transaction_history
- "how much did I spend" (a total across everything)          -> total_spent (NOT spending_by_category)
- "how much on groceries/food/transport" (one named category) -> spending_by_category
- "deposits" / "money coming in" / "credits" / "salary"        -> credits_only (this is the ONLY type for incoming money -- there is no "deposits_only" type)
- a message unrelated to banking, about the assistant itself (e.g. "what's your name"), or too vague/garbled to classify -> unclear (do NOT force it into one of the banking types above just because it contains a stray banking word)

Today is {today}. Resolve relative dates:
- "this month" = first day of current month to today
- "last month" = first to last day of previous month
- "this week"  = last Monday to today
- "last 30 days" = today minus 30 days to today
- "in February" = 2026-02-01 to 2026-02-28
"""

USER_PROMPT = """Message: {message}
Account context: {account_id}"""

# The exact 10 supported types (see module docstring above), plus "greeting".
# Anything else is treated as a hallucinated value and rejected below --
# an LLM occasionally invents a plausible-sounding category (e.g. it once
# returned "deposits_only" instead of the real "credits_only"), and
# nothing downstream knows how to handle a type that doesn't exist.
VALID_QUERY_TYPES = {
    "account_balance", "transaction_history", "large_transactions",
    "account_summary", "payment_confirmation", "spending_by_category",
    "recent_transactions", "credits_only", "total_spent",
    "account_investigation", "greeting", "unclear",
}


_BANKING_RE = re.compile(
    r"\b(balance|account|transaction|spen[dt]|pay(?:ment|ments|ing|s)?\b|paid|deposit|credit|debit|money|funds?\b|transfer|salary|rent\b|grocer|purchase|statement|bank|amount|flag|fees?\b|interest|withdraw|income|expense|cost\b|cheque|savings|overdraft|how much|afford|owe|bill)",
    re.IGNORECASE,
)


def _looks_banking_related(message: str) -> bool:
    """
    Deterministic safety net, independent of the LLM. A small local model
    sometimes forces an obviously off-topic message (e.g. "what is the
    weather today") into a real banking category and then confidently
    writes an answer from unrelated account data. If a message contains no
    banking vocabulary at all, it is treated as unclear regardless of what
    the classifier said.
    """
    return bool(_BANKING_RE.search(message))


def _build_llm():
    """Build the LLM based on configured backend."""
    if settings.llm_backend == "claude":
        from langchain_anthropic import ChatAnthropic
        return ChatAnthropic(
            model=settings.claude_model,
            api_key=settings.anthropic_api_key,
            max_tokens=256,
        )
    else:
        from langchain_ollama import ChatOllama
        return ChatOllama(
            model=settings.ollama_model,
            base_url=settings.ollama_base_url,
            num_predict=256,
        )


def run_requirements_agent(state: PipelineState) -> PipelineState:
    """
    Requirements Agent entry point.
    Called by the LangGraph pipeline with the current state.
    Returns updated state with intent populated.
    """
    logger.info("Requirements Agent — parsing: '%s'", state.message[:80])

    try:
        llm    = _build_llm()
        prompt = ChatPromptTemplate.from_messages([
            ("system", SYSTEM_PROMPT),
            ("human",  USER_PROMPT),
        ])
        chain = prompt | llm | StrOutputParser()

        raw = chain.invoke({
            "today":      date.today().isoformat(),
            "message":    state.message,
            "account_id": state.account_id or "not provided",
        })

        # Strip any accidental markdown fences
        clean = raw.strip().lstrip("```json").lstrip("```").rstrip("```").strip()
        parsed = json.loads(clean)

        intent = QueryIntent(
            query_type=parsed.get("query_type", "account_balance"),
            account_id=parsed.get("account_id") or state.account_id,
            amount_min=parsed.get("amount_min"),
            amount_max=parsed.get("amount_max"),
            date_from=parsed.get("date_from"),
            date_to=parsed.get("date_to"),
            limit=int(parsed.get("limit", 20)),
            description=parsed.get("description"),
            raw_message=state.message,
        )

        if intent.query_type not in VALID_QUERY_TYPES:
            raise ValueError(f"LLM returned an invalid/hallucinated query_type: {intent.query_type!r}")

        logger.info("Requirements Agent — detected query type: %s", intent.query_type)
        state.intent = intent

    except Exception as e:
        logger.warning("Requirements Agent LLM failed (%s) — falling back to keyword matching", e)
        state.intent = _keyword_fallback(state.message, state.account_id)

    if state.intent.query_type not in ("greeting", "unclear") and not _looks_banking_related(state.message):
        logger.info("Requirements Agent — no banking vocabulary in message; treating as unclear")
        state.intent = QueryIntent(query_type="unclear", account_id=state.account_id, raw_message=state.message)

    return state


def _keyword_fallback(message: str, account_id: str | None) -> QueryIntent:
    msg = message.lower()
    # Greeting / general help
    if any(w in msg for w in ["hello", "hi ", "hey", "help", "what can you", "how can you", "capabilities"]):
        return QueryIntent(query_type="greeting", account_id=account_id, raw_message=message)
    # Original keyword fallback below
    """
    Simple keyword-based fallback when the LLM is unavailable.
    Covers all 10 query types using pattern matching.
    """
    msg = message.lower()
    today = date.today()

    # Determine query type
    # NOTE: order matters here -- more specific phrases must be checked
    # before broader ones they overlap with. "How much did I spend..."
    # contains "how much", and "...transactions recently" contains
    # "recent", so the specific total_spent/large_transactions checks
    # have to run first or the broader account_balance/recent_transactions
    # checks win every time and silently swallow them.
    if any(k in msg for k in ["how much did i spend", "spent in", "total spent"]):
        query_type = "total_spent"

    elif any(k in msg for k in ["large", "big transaction", "above", "more than", "over", "greater than", "high value"]):
        query_type = "large_transactions"

    elif any(k in msg for k in ["balance", "how much", "money do i have", "available", "funds", "account balance", "my balance"]):
        query_type = "account_balance"

    elif any(k in msg for k in ["deposit", "credit", "received", "incoming"]):
        query_type = "credits_only"

    elif any(k in msg for k in ["last 10", "last 5", "recent", "latest", "last few"]):
        query_type = "recent_transactions"

    elif any(k in msg for k in ["payment", "go through", "sent", "paid", "transfer"]):
        query_type = "payment_confirmation"

    elif any(k in msg for k in ["groceries", "food", "transport", "spend on", "category"]):
        query_type = "spending_by_category"

    elif any(k in msg for k in ["total"]):
        query_type = "total_spent"

    elif any(k in msg for k in ["summary", "overview", "activity"]):
        query_type = "account_summary"

    elif any(k in msg for k in ["investigation", "staff", "flag", "account 1001"]):
        query_type = "account_investigation"

    else:
        query_type = "transaction_history"

    # Resolve date ranges
    date_from, date_to = None, None
    if "this month" in msg:
        date_from = today.replace(day=1).isoformat()
        date_to   = today.isoformat()
    elif "last month" in msg:
        first_this = today.replace(day=1)
        last_prev  = first_this - timedelta(days=1)
        date_from  = last_prev.replace(day=1).isoformat()
        date_to    = last_prev.isoformat()
    elif "this week" in msg:
        date_from = (today - timedelta(days=today.weekday())).isoformat()
        date_to   = today.isoformat()
    elif "last 30 days" in msg or "30 days" in msg:
        date_from = (today - timedelta(days=30)).isoformat()
        date_to   = today.isoformat()

    # Resolve amount thresholds
    import re
    amount_min = None
    match = re.search(r"(?:above|over|more than|greater than)\s*r?\s*(\d+(?:,\d{3})*(?:\.\d+)?)", msg)
    if match:
        amount_min = float(match.group(1).replace(",", ""))

    return QueryIntent(
        query_type=query_type,
        account_id=account_id,
        amount_min=amount_min,
        date_from=date_from,
        date_to=date_to,
        limit=20,
        raw_message=message,
    )
