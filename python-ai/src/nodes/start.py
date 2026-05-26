import json
from typing import Any, Dict, List, Optional

from .base import BaseNode
from src.core.costing import cost_tracker, estimate_tokens
from src.core.model_runtime import execute_model_completion


class StartNode(BaseNode):
    """起始节点"""

    def __init__(self, node_id: str, data: Dict[str, Any]):
        super().__init__(node_id, "start")
        config = data.get("config", {})
        self.prompt = str(config.get("prompt", data.get("prompt", "")) or "")
        self.initial_variables = config.get("initial_variables", data.get("variables", {})) or {}
        self.input_variables = self._normalize_input_variables(
            config.get("input_variables") or config.get("input_variable_definitions"),
            self.initial_variables,
        )
        self.model_code = config.get("model_code")

    async def execute(self, context) -> Dict[str, Any]:
        for key, value in self.initial_variables.items():
            if self._is_missing(context.get_variable(key)):
                context.add_execution_variable(key, value)

        missing_before_extraction = self._missing_input_variables(context)
        metrics = None
        if missing_before_extraction and self._resolve_model_code(context):
            extracted, metrics = await self._extract_variables(context)
            for key, value in extracted.items():
                if key in missing_before_extraction and not self._is_missing(value):
                    context.add_execution_variable(key, value)

        missing_fields = self._missing_input_variables(context)
        if missing_fields:
            prompt_message = self._build_slot_question(missing_fields)
            result: Dict[str, Any] = {
                "status": "suspended",
                "missing_fields": missing_fields,
                "slot_request": self._build_slot_request(missing_fields),
                "message_deltas": [prompt_message] if prompt_message else [],
                "output": {"missing_fields": missing_fields},
            }
            if metrics:
                result["metrics"] = metrics
            return self.prepare_output(result)

        output = {name: context.get_variable(name) for name in self._declared_variable_names()}
        result = {
            "status": "completed",
            "output": output,
        }
        if metrics:
            result["metrics"] = metrics
        return self.prepare_output(result)

    def _normalize_input_variables(self, source: Any, initial_variables: Dict[str, Any]) -> List[Dict[str, Any]]:
        if isinstance(source, list) and source:
            normalized = []
            for item in source:
                if not isinstance(item, dict):
                    continue
                name = str(item.get("name") or "").strip()
                if not name:
                    continue
                normalized.append({
                    "name": name,
                    "type": str(item.get("type") or "string"),
                    "description": str(item.get("description") or item.get("label") or ""),
                    "default": item.get("default", initial_variables.get(name, "")),
                })
            return normalized

        if isinstance(initial_variables, dict):
            return [
                {"name": str(name), "type": "string", "description": "", "default": value}
                for name, value in initial_variables.items()
            ]
        return []

    def _declared_variable_names(self) -> List[str]:
        return [variable["name"] for variable in self.input_variables if variable.get("name")]

    def _missing_input_variables(self, context) -> List[str]:
        return [name for name in self._declared_variable_names() if self._is_missing(context.get_variable(name))]

    def _resolve_model_code(self, context) -> Optional[str]:
        workflow_defaults = context.workflow_config.get("llm_defaults", {}) if isinstance(context.workflow_config, dict) else {}
        model_code = self.model_code or workflow_defaults.get("model_code") or context.routing_model_code
        return str(model_code) if model_code else None

    async def _extract_variables(self, context) -> tuple[Dict[str, Any], Dict[str, Any]]:
        model_code = self._resolve_model_code(context)
        if not model_code:
            return {}, {}
        prompt_payload = self._build_extraction_prompt(context)
        user_prompt = json.dumps(prompt_payload, ensure_ascii=False)
        completion = await execute_model_completion(
            model_code=model_code,
            provider_configs=context.provider_configs,
            model_records=context.model_records,
            system_prompt=(
                "你是工作流开始节点的槽位提取器，只返回 JSON。"
                "只能处理 start_node.input_variables 中声明的变量。"
                "若能从 user_message 提取缺失变量，则写入 variables；无法提取则放入 missing_fields。"
                "已有非空 current_value 的变量只作为上下文传递，不要覆盖，也不要追问。"
                "不要生成流程外变量、办理步骤、成功承诺或用户可见话术。"
            ),
            user_prompt=user_prompt,
            response_format={"type": "json_object"},
        )
        parsed = self._parse_extraction_output(completion)
        metrics = cost_tracker.build_cost_payload(
            model=self._resolve_upstream_model_code(model_code, context),
            workflow_code=context.workflow_code,
            workflow_version=context.workflow_version,
            execution_id=context.execution_id,
            session_id=context.session_id,
            user_id=context.user_id,
            input_tokens=estimate_tokens(user_prompt),
            output_tokens=estimate_tokens(completion),
        )
        return parsed, metrics

    def _build_extraction_prompt(self, context) -> Dict[str, Any]:
        return {
            "task": "start_node_slot_extraction",
            "user_message": context.get_variable("user_message", ""),
            "start_node": {
                "node_id": self.node_id,
                "prompt": self.prompt,
                "input_variables": [
                    {
                        "name": variable["name"],
                        "type": variable.get("type", "string"),
                        "description": variable.get("description", ""),
                        "default": variable.get("default", ""),
                        "current_value": context.get_variable(variable["name"]),
                    }
                    for variable in self.input_variables
                ],
            },
            "rules": [
                "仅从 user_message 中提取 start_node.input_variables 声明的变量。",
                "current_value 非空的变量不要覆盖、不要追问，只作为后续变量传递。",
                "current_value 为空且无法从 user_message 提取时，将变量名放入 missing_fields。",
            ],
            "required_output": {
                "variables": "对象，只包含已提取到的新变量值",
                "missing_fields": "数组，只包含仍缺失的声明变量名",
            },
        }

    def _parse_extraction_output(self, completion: str) -> Dict[str, Any]:
        try:
            parsed = json.loads(completion or "{}")
        except json.JSONDecodeError as exc:
            raise ValueError(f"Start node slot extraction output is not valid JSON: {completion}") from exc
        if not isinstance(parsed, dict):
            return {}
        variables = parsed.get("variables")
        if isinstance(variables, dict):
            return variables
        return {key: value for key, value in parsed.items() if key in self._declared_variable_names()}

    def _build_slot_request(self, missing_fields: List[str]) -> Dict[str, Any]:
        missing_variables = [variable for variable in self.input_variables if variable["name"] in missing_fields]
        return {
            "prompt": self.prompt,
            "fields": [
                {
                    "name": variable["name"],
                    "type": variable.get("type", "string"),
                    "description": variable.get("description", ""),
                }
                for variable in missing_variables
            ],
        }

    def _build_slot_question(self, missing_fields: List[str]) -> str:
        missing_variables = [variable for variable in self.input_variables if variable["name"] in missing_fields]
        descriptions = [variable.get("description") or variable["name"] for variable in missing_variables]
        return "请提供" + "、".join(descriptions) + "。" if descriptions else self.prompt

    def _resolve_upstream_model_code(self, model_code: str, context) -> str:
        model_record = context.model_records.get(model_code, {})
        return str(model_record.get("upstream_model_code", model_code))

    def _is_missing(self, value: Any) -> bool:
        return value is None or value == "" or value == [] or value == {}
