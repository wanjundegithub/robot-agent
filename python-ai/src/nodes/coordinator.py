import json
import re
from typing import Any, Dict, List, Optional

from .base import BaseNode
from src.core.costing import cost_tracker, estimate_tokens
from src.core.model_runtime import execute_model_completion
from src.core.security import PromptSanitizer
from src.core.system_prompts import (
    DEFAULT_WORKFLOW_CONTROL_SYSTEM_PROMPT,
    resolve_system_prompt,
    system_prompts_from_workflow_config,
)


class CoordinatorNode(BaseNode):
    def __init__(self, node_id: str, data: Dict[str, Any]):
        super().__init__(node_id, "coordinator")
        config = data.get("config", {})
        self.target_variable = str(config.get("target_variable", "targetNodeId"))
        self.default_target = config.get("target_node_id")
        self.reason = str(config.get("reason", "coordinator_selected_target"))
        self.prompt = str(config.get("prompt", data.get("prompt", "")) or "")
        self.system_prompt = str(config.get("system_prompt", "") or "")
        self.user_prompt = str(config.get("user_prompt", "") or "")
        self.model_code = config.get("model_code")

    async def execute(self, context) -> Dict[str, Any]:
        candidates: List[str] = list(context.available_targets or [])
        if not candidates:
            raise ValueError(f"Coordinator node {self.node_id} has no available targets")

        target = self.default_target or self._consume_execution_target(context)
        message_deltas: List[str] = []
        output: Dict[str, Any] = {}
        metrics: Optional[Dict[str, Any]] = None
        security_events: List[Dict[str, Any]] = []

        if len(candidates) > 1 and (not isinstance(target, str) or not target.strip()):
            target = self._resolve_target_from_candidate_text(context, candidates)
            if target:
                output["reason"] = "candidate_text_match"

        if self._should_use_llm():
            if not isinstance(target, str) or not target.strip():
                llm_result = await self._execute_llm_contract(context, candidates)
                target = llm_result.get("targetNodeId") or llm_result.get(self.target_variable) or target
                llm_message = llm_result.get("message") or llm_result.get("welcome_message")
                message_deltas.extend(self._message_deltas(llm_message))
                if isinstance(llm_result.get("reason"), str) and llm_result.get("reason"):
                    output["reason"] = llm_result["reason"]
                if isinstance(llm_result.get("_metrics"), dict):
                    metrics = llm_result["_metrics"]
                security_events = list(llm_result.get("_security_events", []))

        if len(candidates) == 1 and (not isinstance(target, str) or not target.strip()):
            target = candidates[0]
        if not isinstance(target, str) or not target.strip():
            raise ValueError(
                f"Coordinator node {self.node_id} must return targetNodeId when multiple targets exist"
            )
        target = target.strip()
        if target not in candidates:
            raise ValueError(
                f"Invalid targetNodeId '{target}' for coordinator node {self.node_id}; allowed: {candidates}"
            )

        output.update({
            "targetNodeId": target,
            "reason": output.get("reason") or self.reason,
        })
        if message_deltas:
            output["message"] = message_deltas[-1]

        result: Dict[str, Any] = {
            "status": "completed",
            "next_node": target,
            "output": output,
        }
        if message_deltas:
            result["message_deltas"] = message_deltas
        if metrics:
            result["metrics"] = metrics
        if security_events:
            result["security_events"] = security_events

        return self.prepare_output(result)

    def _consume_execution_target(self, context) -> Any:
        key = self.target_variable
        if key in context.execution_variables:
            return context.execution_variables.pop(key)
        return None

    def _resolve_target_from_candidate_text(self, context, candidates: List[str]) -> Optional[str]:
        message_tokens = set(self._route_tokens(context.get_variable("user_message", "")))
        if not message_tokens:
            return None

        best_target: Optional[str] = None
        best_score = 0
        tied = False
        node_context = self._candidate_node_context(context, candidates)
        for target in candidates:
            candidate_tokens: set[str] = set()
            for text in self._candidate_texts(node_context.get(target, {})):
                candidate_tokens.update(self._route_tokens(text))
            if not candidate_tokens:
                continue
            score = len(message_tokens.intersection(candidate_tokens))
            if score <= 0:
                continue
            if score > best_score:
                best_target = target
                best_score = score
                tied = False
            elif score == best_score:
                tied = True

        if best_target and not tied:
            return best_target
        return None

    def _candidate_node_context(self, context, candidates: List[str]) -> Dict[str, Dict[str, Any]]:
        allowed = set(candidates)
        result: Dict[str, Dict[str, Any]] = {}
        node_context = getattr(context, "workflow_node_context", [])
        if not isinstance(node_context, list):
            return result
        for item in node_context:
            if not isinstance(item, dict):
                continue
            node_id = str(item.get("id", "")).strip()
            if node_id in allowed:
                result[node_id] = item
        return result

    def _candidate_texts(self, node: Dict[str, Any]) -> List[str]:
        texts: List[str] = []
        for key in ("name", "title", "label", "description", "prompt", "user_prompt"):
            value = node.get(key)
            if isinstance(value, str) and value.strip():
                texts.append(value)
        return texts

    def _route_tokens(self, value: Any) -> List[str]:
        text = str(value or "").strip().lower()
        if not text:
            return []
        normalized = re.sub(r"[\s,，。.!！?？;；:：、/\\|()（）\[\]{}<>《》\"'“”‘’_-]+", "", text)
        tokens: List[str] = []
        for match in re.finditer(r"[a-z0-9]+", text):
            tokens.append(match.group(0))
        cjk_chars = [char for char in normalized if "\u4e00" <= char <= "\u9fff"]
        cjk = "".join(cjk_chars)
        for size in (2, 3, 4):
            if len(cjk) >= size:
                tokens.extend(cjk[index:index + size] for index in range(0, len(cjk) - size + 1))
        return tokens

    def _should_use_llm(self) -> bool:
        return bool(self.prompt or self.user_prompt or self.system_prompt or self.model_code)

    async def _execute_llm_contract(self, context, candidates: List[str]) -> Dict[str, Any]:
        original_message = context.get_variable("user_message", "")
        message = PromptSanitizer.sanitize(str(original_message or ""))
        security_events: List[Dict[str, Any]] = []
        if message != original_message:
            security_events.append({
                "event_type": "security.prompt_sanitized",
                "data": {
                    "sanitized": True,
                    "original_length": len(str(original_message)),
                    "sanitized_length": len(message),
                },
            })

        model_code = self._resolve_model_code(context)
        system_prompt = self._build_system_prompt_for_context(context)
        user_prompt = self._build_user_prompt(message, context, candidates)
        completion = await execute_model_completion(
            model_code=model_code,
            provider_configs=context.provider_configs,
            model_records=context.model_records,
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            response_format={"type": "json_object"},
        )
        parsed = self._parse_llm_output(completion)
        parsed["_metrics"] = cost_tracker.build_cost_payload(
            model=self._resolve_upstream_model_code(model_code, context),
            workflow_code=context.workflow_code,
            workflow_version=context.workflow_version,
            execution_id=context.execution_id,
            session_id=context.session_id,
            user_id=context.user_id,
            input_tokens=estimate_tokens(user_prompt),
            output_tokens=estimate_tokens(completion),
        )
        if security_events:
            parsed["_security_events"] = security_events
        return parsed

    def _resolve_model_code(self, context) -> str:
        workflow_defaults = context.workflow_config.get("llm_defaults", {}) if isinstance(context.workflow_config, dict) else {}
        model_code = self.model_code or workflow_defaults.get("model_code") or context.routing_model_code
        if not model_code:
            raise ValueError(f"Model code is required for coordinator node {self.node_id}")
        return str(model_code)

    def _resolve_upstream_model_code(self, model_code: str, context) -> str:
        model_record = context.model_records.get(model_code, {})
        return str(model_record.get("upstream_model_code", model_code))

    def _build_system_prompt(self) -> str:
        if self.system_prompt:
            return self.system_prompt
        return DEFAULT_WORKFLOW_CONTROL_SYSTEM_PROMPT

    def _build_system_prompt_for_context(self, context) -> str:
        if self.system_prompt:
            return self.system_prompt
        return resolve_system_prompt(
            system_prompts_from_workflow_config(context.workflow_config),
            "workflow_control",
            DEFAULT_WORKFLOW_CONTROL_SYSTEM_PROMPT,
        )

    def _build_user_prompt(self, message: str, context, candidates: List[str]) -> str:
        instruction = self._render_instruction(message, context)
        payload = {
            "task": "coordinator_routing",
            "node_id": self.node_id,
            "instruction": instruction,
            "user_message": message,
            "candidate_target_node_ids": candidates,
            "required_output": {
                "targetNodeId": "必须是 candidate_target_node_ids 中的一个值",
                "message": "可选。需要对用户输出欢迎语或说明时填写",
                "reason": "可选。简要说明选择原因",
            },
        }
        return json.dumps(payload, ensure_ascii=False)

    def _render_instruction(self, message: str, context) -> str:
        template = self.user_prompt or self.prompt
        if not template:
            return "请选择最合适的下一跳节点。"
        variables = dict(context.execution_variables)
        variables.pop("user_message", None)
        return template.format(user_message=message, **variables)

    def _parse_llm_output(self, completion: str) -> Dict[str, Any]:
        try:
            parsed = json.loads(completion or "{}")
        except json.JSONDecodeError as exc:
            raise ValueError(f"Coordinator LLM output is not valid JSON: {completion}") from exc
        if not isinstance(parsed, dict):
            raise ValueError(f"Coordinator LLM output must be a JSON object: {completion}")
        return parsed

    def _message_deltas(self, message: Any) -> List[str]:
        if not isinstance(message, str):
            return []
        text = message.strip()
        return [text] if text else []
