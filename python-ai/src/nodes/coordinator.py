from typing import Any, Dict, List

from .base import BaseNode


class CoordinatorNode(BaseNode):
    def __init__(self, node_id: str, data: Dict[str, Any]):
        super().__init__(node_id, "coordinator")
        config = data.get("config", {})
        self.target_variable = str(config.get("target_variable", "targetNodeId"))
        self.default_target = config.get("target_node_id")
        self.reason = str(config.get("reason", "coordinator_selected_target"))

    async def execute(self, context) -> Dict[str, Any]:
        candidates: List[str] = list(context.available_targets or [])
        if not candidates:
            raise ValueError(f"Coordinator node {self.node_id} has no available targets")

        if len(candidates) == 1:
            target = candidates[0]
        else:
            target = self.default_target or self._consume_execution_target(context)
            if not isinstance(target, str) or not target.strip():
                raise ValueError(
                    f"Coordinator node {self.node_id} must return targetNodeId when multiple targets exist"
                )
            target = target.strip()
            if target not in candidates:
                raise ValueError(
                    f"Invalid targetNodeId '{target}' for coordinator node {self.node_id}; allowed: {candidates}"
                )

        return self.prepare_output({
            "status": "completed",
            "next_node": target,
            "output": {
                "targetNodeId": target,
                "reason": self.reason,
            },
        })

    def _consume_execution_target(self, context) -> Any:
        key = self.target_variable
        if key in context.execution_variables:
            return context.execution_variables.pop(key)
        return None
