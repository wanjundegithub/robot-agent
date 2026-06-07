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
                output[key] = self._resolve_output_value(context, field_path)

        result = {
            "status": "completed",
            "output": output
        }
        message = self._build_output_message(output)
        if message:
            result["message_deltas"] = [message]
        return self.prepare_output(result)

    def _resolve_output_value(self, context, field_path: Any) -> Any:
        field_path = str(field_path or "").strip()
        if not field_path:
            return None
        if field_path.startswith("$session."):
            return self._resolve_with_scope_fallback(context, field_path[len("$session."):], "session")
        if field_path.startswith("session."):
            return self._resolve_with_scope_fallback(context, field_path[8:], "session")
        if field_path.startswith("$execution."):
            return self._resolve_with_scope_fallback(context, field_path[len("$execution."):], "execution")
        if field_path.startswith("execution."):
            return self._resolve_with_scope_fallback(context, field_path[10:], "execution")
        return context.get_variable(field_path)

    def _resolve_with_scope_fallback(self, context, var_name: str, scope: str) -> Any:
        if scope == "session":
            value = context.session_variables.get(var_name)
            fallback = context.execution_variables.get(var_name)
        else:
            value = context.execution_variables.get(var_name)
            fallback = context.session_variables.get(var_name)
        if value not in (None, "", [], {}):
            return value
        if fallback not in (None, "", [], {}):
            return fallback
        return value

    def _build_output_message(self, output: Dict[str, Any]) -> str:
        values = [
            self._format_value(value)
            for value in output.values()
            if value not in (None, "", [], {})
        ]
        values = [value for value in values if value]
        if not values:
            return ""
        return f"已为您找到：{'；'.join(values)}。"

    def _format_value(self, value: Any) -> str:
        if isinstance(value, list):
            items = [self._format_value(item) for item in value if item not in (None, "", [], {})]
            return "、".join(item for item in items if item)
        if isinstance(value, dict):
            return "，".join(
                f"{key}：{self._format_value(item)}"
                for key, item in value.items()
                if item not in (None, "", [], {})
            )
        return str(value)
