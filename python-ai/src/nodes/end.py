from typing import Dict, Any
from .base import BaseNode


class EndNode(BaseNode):
    """结束节点"""

    def __init__(self, node_id: str, data: Dict[str, Any]):
        super().__init__(node_id, "end")
        config = data.get("config", {})
        self.output_format = config.get("output_format", data.get("output_format", {}))

    async def execute(self, context) -> Dict[str, Any]:
        output = {}
        if self.output_format:
            for key, field_path in self.output_format.items():
                if field_path.startswith("session."):
                    var_name = field_path[8:]
                    output[key] = context.session_variables.get(var_name)
                elif field_path.startswith("execution."):
                    var_name = field_path[10:]
                    output[key] = context.execution_variables.get(var_name)

        return self.prepare_output({
            "status": "completed",
            "output": output
        })
