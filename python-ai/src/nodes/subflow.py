from typing import Any, Dict

from src.core.subflow import run_subflow
from .base import BaseNode


class SubflowNode(BaseNode):
    def __init__(self, node_id: str, data: Dict[str, Any]):
        super().__init__(node_id, "subflow")
        config = data.get("config", {})
        self.subflow_code = config.get("subflow_code")
        self.subflow_version = config.get("subflow_version")
        self.input_mapping = config.get("input_mapping", data.get("input_mapping", {}))
        self.output_mapping = config.get("output_mapping", data.get("output_mapping", {}))

    async def execute(self, context) -> Dict[str, Any]:
        input_variables = self.resolve_input_mapping(self.input_mapping, context)
        if not input_variables:
            input_variables = dict(context.execution_variables)

        output = await run_subflow(
            parent_context=context,
            subflow_code=self.subflow_code,
            subflow_version=self.subflow_version,
            input_variables=input_variables,
        )

        if self.output_mapping:
            self.apply_output_mapping(self.output_mapping, output, context, root_name="subflow")
        else:
            for key, value in output.items():
                context.add_execution_variable(key, value)

        return self.prepare_output({
            "status": "completed",
            "output": output,
            "metrics": {
                "subflow_code": self.subflow_code,
                "subflow_version": self.subflow_version,
            },
        })
