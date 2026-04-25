from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, Optional


def _is_missing(value: Any) -> bool:
    return value is None or value == "" or value == [] or value == {}


@dataclass
class NodeReadiness:
    should_skip: bool
    reason: str = ""
    next_node: Optional[str] = None


class NodeReadinessChecker:
    def check(self, node_def: Dict[str, Any], context, workflow: Dict[str, Any]) -> NodeReadiness:
        if node_def.get("type") != "form":
            return NodeReadiness(should_skip=False)

        fields = node_def.get("config", {}).get("fields", [])
        required_fields = [field.get("name") for field in fields if field.get("required") and field.get("name")]
        missing_fields = [field_name for field_name in required_fields if _is_missing(context.get_variable(field_name))]
        if missing_fields:
            return NodeReadiness(should_skip=False)

        next_node = workflow.get("transitions", {}).get(node_def["id"])
        return NodeReadiness(
            should_skip=True,
            reason="required_fields_already_present",
            next_node=next_node,
        )
