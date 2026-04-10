from typing import Dict, Any
from .base import BaseNode


class StartNode(BaseNode):
    """起始节点"""

    def __init__(self, node_id: str, data: Dict[str, Any]):
        super().__init__(node_id, "start")
        config = data.get("config", {})
        self.initial_variables = config.get("initial_variables", data.get("variables", {}))

    async def execute(self, context) -> Dict[str, Any]:
        for key, value in self.initial_variables.items():
            context.add_execution_variable(key, value)

        return self.prepare_output({
            "status": "completed",
            "output": {}
        })
