from datetime import datetime
import json
from typing import Any, Dict

from src.core.function_fragments import run_function_fragment

from .base import BaseNode


class FunctionNode(BaseNode):
    def __init__(self, node_id: str, data: Dict[str, Any]):
        super().__init__(node_id, "function")
        self.config = data.get("config", {})

    async def execute(self, context) -> Dict[str, Any]:
        language = str(self.config.get("language", "python")).strip().lower()
        if language != "python":
            raise ValueError("函数片段仅支持 python")

        result = run_function_fragment(
            code=str(self.config.get("code", "")),
            variables={
                "global": dict(context.session_variables),
                "local": dict(context.execution_variables),
            },
            timeout_ms=int(self.config.get("timeout_ms", 3000) or 3000),
        )
        if not result["success"]:
            raise ValueError(result["error_message"] or "函数片段执行失败")

        variables = result["variables"]
        preserved_session_variables = _preserved_runtime_variables(context.session_variables)
        preserved_execution_variables = _preserved_runtime_variables(context.execution_variables)
        context.session_variables = {**preserved_session_variables, **dict(variables["global"])}
        context.execution_variables = {**preserved_execution_variables, **dict(variables["local"])}
        context.updated_at = datetime.now()
        return self.prepare_output({
            "status": "completed",
            "operation_type": "fragment",
            "function_name": str(self.config.get("function_name", "")),
            "output": dict(variables["local"]),
            "stdout": result["stdout"],
            "duration_ms": result["duration_ms"],
        })


def _preserved_runtime_variables(values: Dict[str, Any]) -> Dict[str, Any]:
    preserved: Dict[str, Any] = {}
    for key, value in values.items():
        if str(key).startswith("_") or callable(value) or not _json_serializable(value):
            preserved[key] = value
    return preserved


def _json_serializable(value: Any) -> bool:
    try:
        json.dumps(value, ensure_ascii=False)
    except (TypeError, ValueError):
        return False
    return True
