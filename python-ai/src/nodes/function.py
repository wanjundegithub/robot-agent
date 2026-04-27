from typing import Any, Dict

from .base import BaseNode


class FunctionNode(BaseNode):
    def __init__(self, node_id: str, data: Dict[str, Any]):
        super().__init__(node_id, "function")
        self.config = data.get("config", {})

    async def execute(self, context) -> Dict[str, Any]:
        operation_type = str(self.config.get("operation_type", "assign")).strip()
        if operation_type != "assign":
            raise ValueError(f"Unsupported function operation_type: {operation_type}")

        assignments = self.config.get("assignments", {})
        if not isinstance(assignments, dict):
            raise ValueError("Function assign operation requires object assignments")

        resolved = self.resolve_input_mapping(assignments, context)
        context.add_execution_variables(resolved)
        return self.prepare_output({
            "status": "completed",
            "operation_type": "assign",
            "output": resolved,
        })
