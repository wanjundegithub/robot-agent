from typing import Dict, Any
from .base import BaseNode


class ConditionNode(BaseNode):
    """条件判断节点"""

    def __init__(self, node_id: str, data: Dict[str, Any]):
        super().__init__(node_id, "condition")
        config = data.get("config", {})
        self.required_fields = config.get("required_fields")
        self.condition = config.get("condition", data.get("condition", {}))
        self.branches = config.get("branches", data.get("branches", {}))

    async def execute(self, context) -> Dict[str, Any]:
        if self.required_fields:
            missing = [
                field for field in self.required_fields
                if context.get_variable(field) in (None, "", [])
            ]
            branch = "missing" if missing else "complete"
            return self.prepare_output({
                "status": "completed",
                "branch": branch,
                "missing_fields": missing,
                "next_node": self.branches.get(branch)
            })

        condition_type = self.condition.get("type")
        field = self.condition.get("field")
        value = self.condition.get("value")

        actual_value = context.get_variable(field)

        match_result = False
        if condition_type == "equals":
            match_result = actual_value == value
        elif condition_type == "not_equals":
            match_result = actual_value != value
        elif condition_type == "contains":
            match_result = str(value).lower() in str(actual_value).lower()
        elif condition_type == "greater_than":
            try:
                match_result = float(actual_value) > float(value)
            except (ValueError, TypeError):
                match_result = False
        elif condition_type == "less_than":
            try:
                match_result = float(actual_value) < float(value)
            except (ValueError, TypeError):
                match_result = False
        elif condition_type == "regex":
            import re
            try:
                match_result = bool(re.search(str(value), str(actual_value)))
            except re.error:
                match_result = False

        if match_result and "true" in self.branches:
            result = {
                "status": "completed",
                "branch": "true",
                "condition_met": True,
                "next_node": self.branches["true"]
            }
        elif not match_result and "false" in self.branches:
            result = {
                "status": "completed",
                "branch": "false",
                "condition_met": False,
                "next_node": self.branches["false"]
            }
        else:
            result = {
                "status": "completed",
                "branch": None,
                "condition_met": match_result,
                "next_node": None
            }

        return self.prepare_output(result)
