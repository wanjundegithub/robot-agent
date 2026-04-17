import json
from typing import Any, Dict, List

from .base import BaseNode
from src.core.costing import cost_tracker, estimate_tokens
from src.core.model_runtime import execute_profile_completion
from src.core.security import PromptSanitizer, StructuredOutputValidator


class LLMNode(BaseNode):
    """LLM node backed by configured provider + model profiles."""

    def __init__(self, node_id: str, data: Dict[str, Any]):
        super().__init__(node_id, "llm")
        config = data.get("config", {})
        self.prompt = config.get("prompt", data.get("prompt", ""))
        self.structured_output = config.get("structured_output", {})
        self.system_prompt = config.get("system_prompt", "")
        self.user_prompt = config.get("user_prompt", "")
        self.model_profile_ref = config.get("model_profile_ref")
        self.fallback_model_profile_ref = config.get("fallback_model_profile_ref")

    async def execute(self, context) -> Dict[str, Any]:
        original_message = context.get_variable("user_message", "")
        message = PromptSanitizer.sanitize(original_message)
        security_events: List[Dict[str, Any]] = []
        if message != original_message:
            security_events.append({
                "event_type": "security.prompt_sanitized",
                "data": {
                    "sanitized": True,
                    "original_length": len(original_message),
                    "sanitized_length": len(message),
                },
            })

        profile_code = self._resolve_profile_code(context)
        if self.prompt == "knowledge_answer":
            answer = context.get_variable("knowledge_answer")
            if not answer:
                answer = await execute_profile_completion(
                    profile_code=profile_code,
                    provider_configs=context.provider_configs,
                    model_profiles=context.model_profiles,
                    system_prompt="你是服务机器人知识问答助手，请基于提供的知识片段回答用户问题。",
                    user_prompt=self._knowledge_prompt(message, context.get_variable("retrieved_docs", []) or []),
                )
            context.add_execution_variable("answer", answer)
            input_tokens = estimate_tokens(message)
            output_tokens = estimate_tokens(answer)
            cost_metrics = cost_tracker.build_cost_payload(
                model=self._resolve_model_code(profile_code, context),
                workflow_code=context.workflow_code,
                workflow_version=context.workflow_version,
                execution_id=context.execution_id,
                session_id=context.session_id,
                user_id=context.user_id,
                input_tokens=input_tokens,
                output_tokens=output_tokens,
            )
            return self.prepare_output({
                "status": "completed",
                "output": {"answer": answer},
                "message_deltas": [answer],
                "security_events": security_events,
                "metrics": cost_metrics,
            })

        completion = await execute_profile_completion(
            profile_code=profile_code,
            provider_configs=context.provider_configs,
            model_profiles=context.model_profiles,
            system_prompt=self._build_system_prompt(),
            user_prompt=self._build_user_prompt(message, context),
            response_format={"type": "json_object"} if self.structured_output.get("enabled") else None,
        )
        extracted = self._parse_output(completion)
        self._validate_output(extracted)
        if extracted:
            context.add_execution_variables(extracted)

        deltas = ["Slots extracted." if extracted else "No slots extracted, need more info."]
        output_tokens = estimate_tokens(json.dumps(extracted, ensure_ascii=False))
        input_tokens = estimate_tokens(message)
        cost_metrics = cost_tracker.build_cost_payload(
            model=self._resolve_model_code(profile_code, context),
            workflow_code=context.workflow_code,
            workflow_version=context.workflow_version,
            execution_id=context.execution_id,
            session_id=context.session_id,
            user_id=context.user_id,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
        )

        return self.prepare_output({
            "status": "completed",
            "output": extracted,
            "message_deltas": deltas,
            "security_events": security_events,
            "metrics": cost_metrics,
        })

    def _resolve_profile_code(self, context) -> str:
        workflow_defaults = context.workflow_config.get("llm_defaults", {}) if isinstance(context.workflow_config, dict) else {}
        profile_code = self.model_profile_ref or workflow_defaults.get("model_profile_ref") or self.fallback_model_profile_ref
        if not profile_code:
            raise ValueError(f"Model profile ref is required for node {self.node_id}")
        return str(profile_code)

    def _resolve_model_code(self, profile_code: str, context) -> str:
        profile = context.model_profiles.get(profile_code, {})
        return str(profile.get("model_code", profile_code))

    def _build_system_prompt(self) -> str:
        if self.system_prompt:
            return self.system_prompt
        if self.prompt == "hotel_slot_extraction":
            return "你是酒店预订助手，请从用户输入中提取城市、入住日期、入住晚数，并返回 JSON。"
        return "你是服务机器人结构化提取助手，请从用户输入中提取信息，并返回 JSON。"

    def _build_user_prompt(self, message: str, context) -> str:
        if self.user_prompt:
            return self.user_prompt.format(user_message=message, **context.execution_variables)
        if self.prompt == "hotel_slot_extraction":
            return f"用户输入：{message}\n请提取：arrival_city, departure_date, nights。"
        return f"用户输入：{message}\n请提取结构化字段。"

    def _knowledge_prompt(self, message: str, documents: List[Dict[str, Any]]) -> str:
        return json.dumps(
            {
                "question": message,
                "documents": documents,
            },
            ensure_ascii=False,
        )

    def _parse_output(self, completion: str) -> Dict[str, Any]:
        if not completion:
            return {}
        if self.structured_output.get("enabled"):
            try:
                return json.loads(completion)
            except json.JSONDecodeError as exc:
                raise ValueError(f"Structured LLM output is not valid JSON: {completion}") from exc
        return {"text": completion}

    def _validate_output(self, extracted: Dict[str, Any]) -> None:
        if not self.structured_output.get("enabled"):
            return
        schema = self.structured_output.get("schema") or {}
        StructuredOutputValidator.validate(extracted, schema)
