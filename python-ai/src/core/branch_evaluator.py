from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List


def _is_missing(value: Any) -> bool:
    return value is None or value == "" or value == [] or value == {}


@dataclass
class BranchDecision:
    branch: str
    condition_met: bool
    missing_fields: List[str] = field(default_factory=list)
    next_node: str | None = None


class BranchEvaluator:
    def evaluate(self, node_data: Dict[str, Any], context) -> BranchDecision:
        config = node_data.get("config", {})
        required_fields = config.get("required_fields", node_data.get("required_fields", [])) or []
        branches = config.get("branches", node_data.get("branches", {})) or {}
        condition = config.get("condition", node_data.get("condition", {})) or {}

        if required_fields:
            missing_fields = [field for field in required_fields if _is_missing(context.get_variable(field))]
            branch = "missing" if missing_fields else "complete"
            return BranchDecision(
                branch=branch,
                condition_met=not missing_fields,
                missing_fields=missing_fields,
                next_node=branches.get(branch),
            )

        condition_met = self._evaluate_condition(condition, context)
        branch = "true" if condition_met else "false"
        return BranchDecision(
            branch=branch,
            condition_met=condition_met,
            missing_fields=[],
            next_node=branches.get(branch),
        )

    def _evaluate_condition(self, condition: Dict[str, Any], context) -> bool:
        condition_type = str(condition.get("type", "exists"))
        if condition_type == "expression":
            return self._evaluate_expression(str(condition.get("expression", "")), context)

        field_name = condition.get("field")
        actual = context.get_variable(field_name) if field_name else None
        expected = condition.get("value")

        if condition_type == "equals":
            return actual == expected
        if condition_type == "contains":
            if actual is None:
                return False
            return str(expected) in str(actual)
        if condition_type == "greater_than":
            return actual is not None and expected is not None and actual > expected
        if condition_type == "less_than":
            return actual is not None and expected is not None and actual < expected
        if condition_type == "exists":
            return not _is_missing(actual)
        return False

    def _evaluate_expression(self, expression: str, context) -> bool:
        scope = dict(context.session_variables)
        scope.update(context.execution_variables)
        return bool(eval(expression, {"__builtins__": {}}, scope))
