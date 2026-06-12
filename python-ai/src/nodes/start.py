import json
import logging
import re
from typing import Any, Dict, List, Optional

from .base import BaseNode
from src.core.costing import cost_tracker, estimate_tokens
from src.core.model_runtime import execute_model_completion

logger = logging.getLogger(__name__)


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
        if missing_before_extraction:
            rule_extracted = self._extract_variables_with_rules(context, missing_before_extraction)
            for key, value in rule_extracted.items():
                if key in missing_before_extraction and not self._is_missing(value):
                    context.add_execution_variable(key, value)

        missing_before_extraction = self._missing_input_variables(context)
        metrics = None
        if missing_before_extraction and self._resolve_model_code(context):
            try:
                extracted, metrics = await self._extract_variables(context, missing_before_extraction)
            except Exception as exc:
                logger.warning(
                    "start.slot_extraction.failed sessionId=%s executionId=%s nodeId=%s missingFields=%s errorType=%s",
                    context.session_id,
                    context.execution_id,
                    self.node_id,
                    missing_before_extraction,
                    type(exc).__name__,
                )
                extracted, metrics = {}, None
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

    def _extract_variables_with_rules(self, context, missing_fields: List[str]) -> Dict[str, Any]:
        user_message = str(context.get_variable("user_message", "") or "")
        if not user_message.strip():
            return {}
        missing = set(missing_fields)
        extracted: Dict[str, Any] = {}
        for variable in self.input_variables:
            name = variable.get("name")
            if name not in missing:
                continue
            value = self._extract_single_variable_with_rules(variable, user_message)
            if not self._is_missing(value):
                extracted[name] = value
        return extracted

    def _extract_single_variable_with_rules(self, variable: Dict[str, Any], user_message: str) -> Any:
        pattern_value = variable.get("pattern") or variable.get("regex")
        if pattern_value:
            try:
                match = re.search(str(pattern_value), user_message, flags=re.IGNORECASE)
            except re.error:
                match = None
            if match:
                return match.group(1) if match.groups() else match.group(0)

        enum_value = self._extract_enum_value(variable, user_message)
        if not self._is_missing(enum_value):
            return enum_value

        variable_type = str(variable.get("type") or "string").strip().lower()
        lookup_text = " ".join([
            str(variable.get("name") or ""),
            str(variable.get("description") or ""),
            variable_type,
        ]).casefold()

        if variable_type in {"integer", "int", "long"}:
            return self._extract_integer(user_message)
        if variable_type in {"number", "float", "double", "decimal"}:
            return self._extract_number(user_message)
        if variable_type in {"boolean", "bool"}:
            return self._extract_boolean(user_message)
        if "email" in lookup_text:
            return self._extract_regex(user_message, r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}")
        if any(token in lookup_text for token in ("phone", "mobile", "tel")):
            return self._extract_regex(user_message, r"\+?\d[\d\s-]{6,}\d")
        if "url" in lookup_text or "uri" in lookup_text:
            return self._extract_regex(user_message, r"https?://[^\s]+")
        if "date" in lookup_text:
            return self._extract_regex(user_message, r"\b\d{4}-\d{1,2}-\d{1,2}\b|\b\d{1,2}/\d{1,2}/\d{2,4}\b")
        if "time" in lookup_text:
            return self._extract_regex(user_message, r"\b\d{1,2}:\d{2}(?::\d{2})?\b")
        return None

    def _extract_enum_value(self, variable: Dict[str, Any], user_message: str) -> Any:
        for label, value in self._enum_candidates(variable):
            if not label:
                continue
            if self._message_contains_option(user_message, label):
                return value
        return None

    def _enum_candidates(self, variable: Dict[str, Any]) -> List[tuple[str, Any]]:
        candidates: List[tuple[str, Any]] = []
        for key in ("enum", "enums", "options", "values", "allowed_values"):
            raw = variable.get(key)
            if isinstance(raw, dict):
                candidates.extend((str(label), value) for label, value in raw.items())
            elif isinstance(raw, list):
                for item in raw:
                    if isinstance(item, dict):
                        value = item.get("value", item.get("name", item.get("label")))
                        for label_key in ("label", "name", "value"):
                            label = item.get(label_key)
                            if label not in (None, ""):
                                candidates.append((str(label), value))
                    elif item not in (None, ""):
                        candidates.append((str(item), item))
        return candidates

    def _message_contains_option(self, user_message: str, option: str) -> bool:
        option = str(option or "").strip()
        if not option:
            return False
        if re.fullmatch(r"[\w\s.-]+", option):
            return re.search(rf"(?<!\w){re.escape(option)}(?!\w)", user_message, flags=re.IGNORECASE) is not None
        return option.casefold() in user_message.casefold()

    def _extract_integer(self, user_message: str) -> Optional[int]:
        match = re.search(r"(?<![\w.])-?\d+(?![\w.])", user_message)
        return int(match.group(0)) if match else None

    def _extract_number(self, user_message: str) -> Optional[float | int]:
        match = re.search(r"(?<![\w.])-?\d+(?:\.\d+)?(?![\w.])", user_message)
        if not match:
            return None
        text = match.group(0)
        return float(text) if "." in text else int(text)

    def _extract_boolean(self, user_message: str) -> Optional[bool]:
        normalized = user_message.casefold()
        if re.search(r"(?<!\w)(true|yes|y|on|enabled|enable)(?!\w)", normalized):
            return True
        if re.search(r"(?<!\w)(false|no|n|off|disabled|disable)(?!\w)", normalized):
            return False
        return None

    def _extract_regex(self, user_message: str, pattern: str) -> Optional[str]:
        match = re.search(pattern, user_message, flags=re.IGNORECASE)
        return match.group(0) if match else None

    async def _extract_variables(self, context, missing_fields: Optional[List[str]] = None) -> tuple[Dict[str, Any], Dict[str, Any]]:
        model_code = self._resolve_model_code(context)
        if not model_code:
            return {}, {}
        prompt_payload = self._build_extraction_prompt(context, missing_fields)
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
                "Return exactly one valid JSON object only. "
                "The root object must be {\"variables\": {...}, \"missing_fields\": [...]}. "
                "Do not output Markdown, comments, duplicate JSON objects, trailing commas, or any text outside JSON. "
            ),
            user_prompt=user_prompt,
            response_format={"type": "json_object"},
            max_tokens=65535,
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

    def _build_extraction_prompt(self, context, missing_fields: Optional[List[str]] = None) -> Dict[str, Any]:
        requested_names = set(missing_fields or self._declared_variable_names())
        target_variables = [
            variable
            for variable in self.input_variables
            if variable.get("name") in requested_names
        ]
        known_variables = {
            variable["name"]: context.get_variable(variable["name"])
            for variable in self.input_variables
            if variable.get("name") not in requested_names and not self._is_missing(context.get_variable(variable["name"]))
        }
        return {
            "task": "start_node_slot_extraction",
            "user_message": context.get_variable("user_message", ""),
            "start_node": {
                "node_id": self.node_id,
                "prompt": self.prompt,
                "known_variables": known_variables,
                "input_variables": [
                    {
                        "name": variable["name"],
                        "type": variable.get("type", "string"),
                        "description": variable.get("description", ""),
                        "default": variable.get("default", ""),
                        "current_value": context.get_variable(variable["name"]),
                    }
                    for variable in target_variables
                ],
            },
            "rules": [
                "仅从 user_message 中提取 start_node.input_variables 声明的变量。",
                "current_value 非空的变量不要覆盖、不要追问，只作为后续变量传递。",
                "current_value 为空且无法从 user_message 提取时，将变量名放入 missing_fields。",
            ],
            "output_contract": {
                "json_only": True,
                "root_object": {"variables": {}, "missing_fields": []},
                "no_markdown": True,
                "no_explanations": True,
                "no_duplicate_root_objects": True,
            },
            "required_output": {
                "variables": "对象，只包含已提取到的新变量值",
                "missing_fields": "数组，只包含仍缺失的声明变量名",
            },
        }

    def _parse_extraction_output(self, completion: str) -> Dict[str, Any]:
        parsed = self._load_json_object(completion)
        if not isinstance(parsed, dict):
            return {}
        declared_names = set(self._declared_variable_names())
        variables = parsed.get("variables")
        if isinstance(variables, dict):
            return {key: value for key, value in variables.items() if key in declared_names}
        return {key: value for key, value in parsed.items() if key in declared_names}

    def _load_json_object(self, completion: str) -> Dict[str, Any]:
        text = (completion or "").strip()
        if not text:
            return {}
        try:
            parsed = json.loads(text)
        except json.JSONDecodeError:
            candidates = self._json_object_candidates(text)
            if not candidates:
                return {}
            declared_names = set(self._declared_variable_names())
            ranked = [
                (self._json_candidate_score(candidate, declared_names), index, candidate)
                for index, candidate in enumerate(candidates)
            ]
            score, _index, candidate = max(ranked, key=lambda item: (item[0], item[1]))
            return candidate if score > 0 else {}
        return parsed if isinstance(parsed, dict) else {}

    def _json_object_candidates(self, text: str) -> List[Dict[str, Any]]:
        decoder = json.JSONDecoder()
        candidates: List[Dict[str, Any]] = []
        for index, char in enumerate(text):
            if char != "{":
                continue
            try:
                parsed, _end = decoder.raw_decode(text[index:])
            except json.JSONDecodeError:
                continue
            if isinstance(parsed, dict):
                candidates.append(parsed)
        return candidates

    def _json_candidate_score(self, candidate: Dict[str, Any], declared_names: set) -> int:
        variables = candidate.get("variables")
        if isinstance(variables, dict):
            if any(key in declared_names for key in variables):
                return 3
            if "missing_fields" in candidate:
                return 1
            return 1
        return 0

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
