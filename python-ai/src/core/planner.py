from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List


def _is_missing(value: Any) -> bool:
    return value is None or value == "" or value == [] or value == {}


@dataclass
class ExecutionPlan:
    current_node_id: str
    candidate_nodes: List[str]
    reasoning_summary: str
    confidence: float = 1.0
    need_user_input: bool = False
    missing_fields: List[str] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "current_node_id": self.current_node_id,
            "candidate_nodes": list(self.candidate_nodes),
            "reasoning_summary": self.reasoning_summary,
            "confidence": self.confidence,
            "need_user_input": self.need_user_input,
            "missing_fields": list(self.missing_fields),
        }


class Planner:
    def plan(self, planning_context) -> ExecutionPlan:
        node_def = planning_context.current_node
        node_type = node_def.get("type", "unknown")
        missing_fields: List[str] = []

        if node_type == "condition":
            required_fields = node_def.get("config", {}).get("required_fields", [])
            missing_fields = [
                field_name
                for field_name in required_fields
                if _is_missing(planning_context.context.get_variable(field_name))
            ]

        return ExecutionPlan(
            current_node_id=planning_context.current_node_id,
            candidate_nodes=[planning_context.current_node_id],
            reasoning_summary=f"Execute {node_type} node {planning_context.current_node_id}",
            confidence=1.0,
            need_user_input=node_type == "form",
            missing_fields=missing_fields,
        )
