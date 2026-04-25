from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List


@dataclass
class WorkflowJournal:
    entries: List[Dict[str, Any]] = field(default_factory=list)

    def record_plan(self, plan: Dict[str, Any]) -> None:
        self.entries.append({"type": "plan", **plan})

    def record_skip(self, node_id: str, reason: str) -> None:
        self.entries.append({"type": "skip", "node_id": node_id, "reason": reason})

    def record_execution(self, node_id: str, status: str, output: Dict[str, Any]) -> None:
        self.entries.append({
            "type": "execution",
            "node_id": node_id,
            "status": status,
            "output": dict(output),
        })
