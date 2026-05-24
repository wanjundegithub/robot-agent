from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from src.core.model_runtime import execute_model_completion


def _is_missing(value: Any) -> bool:
    return value is None or value == "" or value == [] or value == {}


@dataclass
class ReactDecision:
    target_node_id: str
    action: str
    missing_fields: List[str] = field(default_factory=list)
    confidence: float = 1.0
    reason_summary: str = ""


class ReactDecisionService:
    """Internal ReAct-style transition decision service.

    The workflow graph does not expose a ReAct node. The scheduler invokes this
    service when a completed node has multiple legal successors. It prefers a
    model-based JSON contract when runtime model configuration is available and
    falls back to deterministic schema-bound reasoning for stability.
    """

    def __init__(self) -> None:
        self.logger = logging.getLogger(__name__)

    async def decide_next_node(
        self,
        current_node: Dict[str, Any],
        result: Dict[str, Any],
        candidates: List[Dict[str, Any]],
        context,
    ) -> ReactDecision:
        explicit_target = self._extract_explicit_target(result)
        if explicit_target:
            if explicit_target not in {candidate["target_node_id"] for candidate in candidates}:
                raise ValueError(
                    f"Invalid targetNodeId '{explicit_target}' for node {current_node.get('id')}; "
                    f"allowed: {[candidate['target_node_id'] for candidate in candidates]}"
                )
            return ReactDecision(
                target_node_id=explicit_target,
                action=self._action_for_candidate(self._candidate_by_id(candidates, explicit_target)),
                confidence=1.0,
                reason_summary="Explicit targetNodeId selected by node output.",
            )

        model_decision = await self._decide_with_model(current_node, result, candidates, context)
        if model_decision is not None:
            return model_decision

        return self._decide_with_rules(candidates, context)

    def _decide_with_rules(self, candidates: List[Dict[str, Any]], context) -> ReactDecision:
        missing_form = self._select_missing_form_candidate(candidates, context)
        if missing_form is not None:
            target_node_id, missing_fields = missing_form
            return ReactDecision(
                target_node_id=target_node_id,
                action="ask_user",
                missing_fields=missing_fields,
                confidence=0.95,
                reason_summary="Required fields are missing; route to user input collection.",
            )

        ready_tool = self._select_ready_tool_candidate(candidates, context)
        if ready_tool is not None:
            return ReactDecision(
                target_node_id=ready_tool,
                action="call_tool",
                missing_fields=[],
                confidence=0.9,
                reason_summary="Required fields are available; route to tool execution.",
            )

        selected = self._select_preferred_candidate(candidates)
        return ReactDecision(
            target_node_id=selected["target_node_id"],
            action=self._action_for_candidate(selected),
            missing_fields=[],
            confidence=0.6,
            reason_summary="No explicit target; selected the highest-priority allowed successor.",
        )

    async def _decide_with_model(
        self,
        current_node: Dict[str, Any],
        result: Dict[str, Any],
        candidates: List[Dict[str, Any]],
        context,
    ) -> Optional[ReactDecision]:
        model_code = self._resolve_model_code(context)
        if not model_code:
            return None
        if model_code not in context.model_records:
            return None

        candidate_ids = [candidate["target_node_id"] for candidate in candidates]
        try:
            completion = await execute_model_completion(
                model_code=model_code,
                provider_configs=context.provider_configs,
                model_records=context.model_records,
                system_prompt=(
                    "你是工作流运行时的内部 ReAct 决策器。"
                    "根据 Thought/Action/Observation 推理下一跳，但只返回 JSON，不能输出额外文本。"
                    "targetNodeId 必须来自候选节点。"
                ),
                user_prompt=json.dumps(
                    {
                        "task": "internal_react_transition_decision",
                        "current_node": self._summarize_node(current_node),
                        "observation": {
                            "node_output": result.get("output", {}),
                            "execution_variables": dict(context.execution_variables),
                            "session_variables": dict(context.session_variables),
                        },
                        "candidate_nodes": [self._summarize_candidate(candidate) for candidate in candidates],
                        "allowed_target_node_ids": candidate_ids,
                        "required_output": {
                            "targetNodeId": "必须是 allowed_target_node_ids 中的一个值",
                            "action": "ask_user | call_tool | final_answer | continue",
                            "missingFields": "缺失字段数组，没有则为空数组",
                            "confidence": "0 到 1 的数字",
                            "reasonSummary": "简短中文原因",
                        },
                    },
                    ensure_ascii=False,
                ),
                response_format={"type": "json_object"},
            )
            parsed = json.loads(completion or "{}")
            target_node_id = parsed.get("targetNodeId")
            if target_node_id not in candidate_ids:
                raise ValueError(f"Invalid model targetNodeId: {target_node_id}")
            candidate = self._candidate_by_id(candidates, str(target_node_id))
            missing_form = self._select_missing_form_candidate(candidates, context)
            if missing_form is not None and str(target_node_id) != missing_form[0]:
                raise ValueError(
                    f"Model selected {target_node_id} while required fields are missing: {missing_form[1]}"
                )
            ready_tool = self._select_ready_tool_candidate(candidates, context)
            if missing_form is None and candidate.get("node", {}).get("type") == "form" and ready_tool:
                raise ValueError(
                    f"Model selected form {target_node_id} while required fields are complete"
                )
            missing_fields = parsed.get("missingFields", parsed.get("missing_fields", []))
            if not isinstance(missing_fields, list):
                missing_fields = []
            return ReactDecision(
                target_node_id=str(target_node_id),
                action=str(parsed.get("action") or self._action_for_candidate(candidate)),
                missing_fields=[str(field) for field in missing_fields],
                confidence=self._clamp_confidence(parsed.get("confidence")),
                reason_summary=str(parsed.get("reasonSummary") or parsed.get("reason") or "Model selected transition."),
            )
        except Exception as exc:
            self.logger.warning(
                "react.decision.model_fallback nodeId=%s modelCode=%s message=%s",
                current_node.get("id"),
                model_code,
                exc,
            )
            return None

    def _resolve_model_code(self, context) -> Optional[str]:
        workflow_config = context.workflow_config if isinstance(context.workflow_config, dict) else {}
        decision_policy = workflow_config.get("decision_policy") or workflow_config.get("react_decision") or {}
        if isinstance(decision_policy, dict) and str(decision_policy.get("mode", "model")).lower() == "rules":
            return None
        if isinstance(decision_policy, dict) and decision_policy.get("model_code"):
            return str(decision_policy["model_code"])
        llm_defaults = workflow_config.get("llm_defaults", {})
        if isinstance(llm_defaults, dict) and llm_defaults.get("model_code"):
            return str(llm_defaults["model_code"])
        if context.routing_model_code:
            return str(context.routing_model_code)
        return None

    def _summarize_node(self, node: Dict[str, Any]) -> Dict[str, Any]:
        return {
            "id": node.get("id"),
            "type": node.get("type"),
            "config_keys": sorted((node.get("config") or {}).keys()),
        }

    def _summarize_candidate(self, candidate: Dict[str, Any]) -> Dict[str, Any]:
        node = candidate.get("node", {})
        return {
            "targetNodeId": candidate.get("target_node_id"),
            "branch": candidate.get("branch"),
            "description": candidate.get("description"),
            "node": self._summarize_node(node),
            "required_fields": self._required_form_fields(node) if node.get("type") == "form" else [],
            "tool_code": (node.get("config") or {}).get("tool_code"),
        }

    def _required_form_fields(self, node: Dict[str, Any]) -> List[str]:
        fields = node.get("config", {}).get("fields", [])
        return [str(field.get("name")) for field in fields if field.get("required") and field.get("name")]

    def _clamp_confidence(self, value: Any) -> float:
        try:
            confidence = float(value)
        except (TypeError, ValueError):
            return 0.8
        return max(0.0, min(1.0, confidence))

    def _extract_explicit_target(self, result: Dict[str, Any]) -> Optional[str]:
        for key in ("next_node", "targetNodeId"):
            value = result.get(key)
            if isinstance(value, str) and value:
                return value
        output = result.get("output")
        if isinstance(output, dict):
            value = output.get("targetNodeId")
            if isinstance(value, str) and value:
                return value
        return None

    def _select_missing_form_candidate(self, candidates: List[Dict[str, Any]], context) -> Optional[tuple[str, List[str]]]:
        for candidate in candidates:
            node = candidate.get("node", {})
            if node.get("type") != "form":
                continue
            missing_fields = self._missing_required_form_fields(node, context)
            if missing_fields:
                return candidate["target_node_id"], missing_fields
        return None

    def _select_ready_tool_candidate(self, candidates: List[Dict[str, Any]], context) -> Optional[str]:
        form_required_fields = self._all_required_form_fields(candidates)
        if form_required_fields and any(_is_missing(context.get_variable(field_name)) for field_name in form_required_fields):
            return None
        for candidate in candidates:
            node = candidate.get("node", {})
            if node.get("type") in {"tool", "subflow", "message", "end"}:
                return candidate["target_node_id"]
        return None

    def _select_preferred_candidate(self, candidates: List[Dict[str, Any]]) -> Dict[str, Any]:
        if not candidates:
            raise ValueError("No transition candidates available for internal decision")
        for node_type in ("form", "tool", "subflow", "message", "end"):
            for candidate in candidates:
                if candidate.get("node", {}).get("type") == node_type:
                    return candidate
        return candidates[0]

    def _candidate_by_id(self, candidates: List[Dict[str, Any]], target_node_id: str) -> Dict[str, Any]:
        for candidate in candidates:
            if candidate["target_node_id"] == target_node_id:
                return candidate
        return {}

    def _missing_required_form_fields(self, node: Dict[str, Any], context) -> List[str]:
        fields = node.get("config", {}).get("fields", [])
        required_fields = [field.get("name") for field in fields if field.get("required") and field.get("name")]
        return [field_name for field_name in required_fields if _is_missing(context.get_variable(field_name))]

    def _all_required_form_fields(self, candidates: List[Dict[str, Any]]) -> List[str]:
        required_fields: List[str] = []
        for candidate in candidates:
            node = candidate.get("node", {})
            if node.get("type") != "form":
                continue
            for field in node.get("config", {}).get("fields", []):
                name = field.get("name")
                if field.get("required") and name and name not in required_fields:
                    required_fields.append(name)
        return required_fields

    def _action_for_candidate(self, candidate: Dict[str, Any]) -> str:
        node_type = candidate.get("node", {}).get("type")
        if node_type == "form":
            return "ask_user"
        if node_type in {"tool", "subflow"}:
            return "call_tool"
        if node_type == "end":
            return "final_answer"
        return "continue"
