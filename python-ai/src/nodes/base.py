from abc import ABC, abstractmethod
from typing import Dict, Any
from datetime import datetime


class BaseNode(ABC):
    """基础节点抽象类"""

    def __init__(self, node_id: str, node_type: str):
        self.node_id = node_id
        self.node_type = node_type
        self.created_at = datetime.now()

    @abstractmethod
    async def execute(self, context) -> Dict[str, Any]:
        """执行节点逻辑"""
        pass

    def prepare_output(self, result: Dict[str, Any]) -> Dict[str, Any]:
        result.update({
            "node_id": self.node_id,
            "node_type": self.node_type,
            "timestamp": datetime.now().isoformat()
        })
        return result

    def resolve_input_mapping(self, mapping: Dict[str, Any], context) -> Dict[str, Any]:
        resolved: Dict[str, Any] = {}
        for key, value in mapping.items():
            resolved[key] = self._resolve_value(value, {}, context)
        return resolved

    def apply_output_mapping(self, mapping: Dict[str, Any], source: Dict[str, Any], context, root_name: str = "node") -> None:
        for target, reference in mapping.items():
            value = self._resolve_reference(str(reference), source, context, root_name=root_name)
            self._assign_target(target, value, context)

    def _resolve_value(self, value: Any, source: Dict[str, Any], context) -> Any:
        if isinstance(value, str) and value.startswith("$"):
            return self._resolve_reference(value, source, context)
        if isinstance(value, dict):
            return {key: self._resolve_value(item, source, context) for key, item in value.items()}
        if isinstance(value, list):
            return [self._resolve_value(item, source, context) for item in value]
        return value

    def _resolve_reference(self, reference: str, source: Dict[str, Any], context, root_name: str = "node") -> Any:
        if reference.startswith("$execution."):
            return self._read_nested(context.execution_variables, reference[len("$execution."):])
        if reference.startswith("$session."):
            return self._read_nested(context.session_variables, reference[len("$session."):])
        prefix = f"${root_name}.output."
        if reference.startswith(prefix):
            return self._read_nested(source, reference[len(prefix):])
        return None

    def _assign_target(self, target: str, value: Any, context) -> None:
        if target.startswith("$execution."):
            context.add_execution_variable(target[len("$execution."):], value)
            return
        if target.startswith("$session."):
            context.add_session_variable(target[len("$session."):], value)

    def _read_nested(self, source: Dict[str, Any], path: str) -> Any:
        current: Any = source
        for segment in path.split("."):
            if not segment:
                continue
            if isinstance(current, dict):
                current = current.get(segment)
                continue
            if isinstance(current, list) and segment.isdigit():
                index = int(segment)
                current = current[index] if 0 <= index < len(current) else None
                continue
            return None
        return current
