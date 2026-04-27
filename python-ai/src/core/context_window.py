from dataclasses import dataclass, field
from typing import Any, Dict, List


@dataclass
class ContextWindow:
    recent_messages: List[Dict[str, Any]] = field(default_factory=list)
    summary: str = ""


class ContextWindowManager:
    """Builds a compact conversation window for planning decisions."""

    def __init__(self, max_recent_messages: int = 6):
        self.max_recent_messages = max_recent_messages

    def build(self, context) -> ContextWindow:
        user_message = context.get_variable("user_message", "")
        recent_messages: List[Dict[str, Any]] = []
        if user_message:
            recent_messages.append({"role": "user", "content": user_message})

        summary = ""
        if context.skipped_nodes:
            summary = f"Skipped nodes: {', '.join(context.skipped_nodes[-3:])}"

        return ContextWindow(
            recent_messages=recent_messages[: self.max_recent_messages],
            summary=summary,
        )
