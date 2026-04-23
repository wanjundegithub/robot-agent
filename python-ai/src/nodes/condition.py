from typing import Dict, Any

from src.core.branch_evaluator import BranchEvaluator

from .base import BaseNode


class ConditionNode(BaseNode):
    """条件判断节点"""

    def __init__(self, node_id: str, data: Dict[str, Any]):
        super().__init__(node_id, "condition")
        config = data.get("config", {})
        self.required_fields = config.get("required_fields")
        self.condition = config.get("condition", data.get("condition", {}))
        self.branches = config.get("branches", data.get("branches", {}))
        self.evaluator = BranchEvaluator()
        self.data = data

    async def execute(self, context) -> Dict[str, Any]:
        decision = self.evaluator.evaluate(self.data, context)
        return self.prepare_output({
            "status": "completed",
            "branch": decision.branch,
            "condition_met": decision.condition_met,
            "missing_fields": decision.missing_fields,
            "next_node": decision.next_node,
        })
