from __future__ import annotations

import json
import logging
import os
import time
from typing import Any, Callable, Dict, List
from urllib.parse import quote

import httpx
from src.core.logging_utils import duration_ms, safe_url, sanitize_dict, summarize_payload
from src.core.system_prompts import DEFAULT_INTENT_ROUTING_SYSTEM_PROMPT, resolve_system_prompt


class ModelConfigError(Exception):
    pass


class ModelExecutionError(Exception):
    pass


logger = logging.getLogger(__name__)


def _provider_extra_headers(provider: Dict[str, Any]) -> Dict[str, Any]:
    raw_headers = provider.get("extra_headers", {})
    if not isinstance(raw_headers, dict):
        return {}
    return raw_headers


def _provider_meta(provider: Dict[str, Any]) -> Dict[str, Any]:
    extra_headers = _provider_extra_headers(provider)
    meta = extra_headers.get("__meta__")
    return meta if isinstance(meta, dict) else {}


def _provider_protocol(provider: Dict[str, Any]) -> str:
    provider_type = str(provider.get("provider_type", "")).strip().lower()
    meta_protocol = str(_provider_meta(provider).get("protocol", "")).strip().lower()
    if meta_protocol:
        return meta_protocol
    if provider_type == "custom":
        return "openai"
    return provider_type


def _join_url(base_url: str, path: str) -> str:
    normalized_base = base_url.rstrip("/")
    normalized_path = path.strip()
    if not normalized_path:
        return normalized_base
    if normalized_path.startswith("http://") or normalized_path.startswith("https://"):
        return normalized_path
    if not normalized_path.startswith("/"):
        normalized_path = "/" + normalized_path
    if normalized_base.endswith(normalized_path):
        return normalized_base
    return f"{normalized_base}{normalized_path}"


def resolve_provider(provider_configs: Dict[str, Dict[str, Any]], provider_code: str) -> Dict[str, Any]:
    provider = provider_configs.get(provider_code)
    if not provider:
        raise ModelConfigError(f"Provider config not found: {provider_code}")
    return provider


def resolve_model_record(model_records: Dict[str, Dict[str, Any]], model_code: str) -> Dict[str, Any]:
    model_record = model_records.get(model_code)
    if not model_record:
        raise ModelConfigError(f"Model record not found: {model_code}")
    return model_record


def _model_default_options(model_record: Dict[str, Any]) -> Dict[str, Any]:
    raw_options = model_record.get("default_options")
    if isinstance(raw_options, dict):
        return raw_options

    raw_json = model_record.get("default_options_json")
    if isinstance(raw_json, dict):
        return raw_json
    if isinstance(raw_json, str) and raw_json.strip():
        try:
            parsed = json.loads(raw_json)
        except json.JSONDecodeError:
            return {}
        return parsed if isinstance(parsed, dict) else {}
    return {}


def _model_option(model_record: Dict[str, Any], option_key: str, default_value: Any) -> Any:
    options = _model_default_options(model_record)
    if option_key in options and options.get(option_key) is not None:
        return options.get(option_key)
    return default_value


def _build_openai_compatible_body(
    upstream_model_code: str,
    system_prompt: str,
    user_prompt: str,
    temperature: float,
    top_p: float,
    max_tokens: int,
) -> Dict[str, Any]:
    return {
        "model": upstream_model_code,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        "temperature": temperature,
        "top_p": top_p,
        "max_tokens": max_tokens,
    }


async def classify_intent_with_model_code(
    message: str,
    candidate_workflows: List[Dict[str, Any]],
    routing_model_code: str,
    provider_configs: Dict[str, Dict[str, Any]],
    model_records: Dict[str, Dict[str, Any]],
    system_prompts: Dict[str, Any] | None = None,
) -> Dict[str, Any]:
    prompt = {
        "task": "intent_routing",
        "message": message,
        "candidate_workflows": candidate_workflows,
        "required_fields": [
            "matched",
            "intent_code",
            "workflow_code",
            "target_type",
            "target_code",
            "confidence",
            "reason",
            "need_clarification",
            "clarification_question",
        ],
        "fallback_message_requirements": {
            "when": "matched=false or the requested service is not available in candidate_workflows",
            "field": "clarification_question",
            "style": "brief, polite Chinese customer-service fallback; mention the user's requested service when it can be inferred; ask if another available service is needed",
            "example": "抱歉，当前无法为您提供该服务，您还需要其他服务吗？",
        },
    }
    content = await execute_model_completion(
        model_code=routing_model_code,
        provider_configs=provider_configs,
        model_records=model_records,
        system_prompt=resolve_system_prompt(
            system_prompts,
            "intent_routing",
            DEFAULT_INTENT_ROUTING_SYSTEM_PROMPT,
        ),
        user_prompt=json.dumps(prompt, ensure_ascii=False),
        response_format={"type": "json_object"},
    )
    try:
        parsed = json.loads(content)
    except json.JSONDecodeError as exc:
        raise ModelExecutionError(f"Intent classification returned invalid JSON: {content}") from exc
    return _normalize_intent_result(parsed)


def _normalize_intent_result(parsed: Dict[str, Any]) -> Dict[str, Any]:
    matched = bool(parsed.get("matched"))
    confidence = _clamp_confidence(parsed.get("confidence"))
    reason = parsed.get("reason")
    normalized_reason = str(reason).strip() if reason is not None else ""

    if matched:
        workflow_code = parsed.get("workflow_code")
        if workflow_code is None or not str(workflow_code).strip():
            raise ModelExecutionError("Intent classification missing workflow_code when matched=true")
        workflow_code = str(workflow_code).strip()
        intent_code = parsed.get("intent_code")
        target_type = parsed.get("target_type")
        target_code = parsed.get("target_code")
        return {
            "matched": True,
            "intent_code": str(intent_code).strip() if intent_code is not None else None,
            "workflow_code": workflow_code,
            "target_type": str(target_type).strip() if target_type is not None and str(target_type).strip() else "workflow",
            "target_code": str(target_code).strip() if target_code is not None and str(target_code).strip() else workflow_code,
            "confidence": confidence,
            "reason": normalized_reason,
            "need_clarification": bool(parsed.get("need_clarification", False)),
            "clarification_question": parsed.get("clarification_question"),
        }

    clarification_question = parsed.get("clarification_question")
    normalized_question = str(clarification_question).strip() if clarification_question is not None else ""
    if not normalized_question:
        normalized_question = "请问您想办理哪类业务？"

    return {
        "matched": False,
        "intent_code": None,
        "workflow_code": None,
        "target_type": None,
        "target_code": None,
        "confidence": confidence,
        "reason": normalized_reason,
        "need_clarification": True,
        "clarification_question": normalized_question,
    }


def _clamp_confidence(confidence: Any) -> float:
    try:
        value = float(confidence)
    except (TypeError, ValueError):
        return 0.0
    if value < 0.0:
        return 0.0
    if value > 1.0:
        return 1.0
    return value


async def execute_model_completion(
    model_code: str,
    provider_configs: Dict[str, Dict[str, Any]],
    model_records: Dict[str, Dict[str, Any]],
    system_prompt: str | None,
    user_prompt: str,
    response_format: Dict[str, Any] | None = None,
    stream_callback: Callable[[str, bool], None] | None = None,
) -> str:
    model_record = resolve_model_record(model_records, model_code)
    provider = resolve_provider(provider_configs, str(model_record.get("provider_code")))
    return await _invoke_provider(provider, model_record, system_prompt, user_prompt, response_format, stream_callback)


async def _invoke_provider(
    provider: Dict[str, Any],
    model_record: Dict[str, Any],
    system_prompt: str | None,
    user_prompt: str,
    response_format: Dict[str, Any] | None = None,
    stream_callback: Callable[[str, bool], None] | None = None,
) -> str:
    start_time = time.perf_counter()
    provider_type = str(provider.get("provider_type", "")).strip().lower()
    protocol = _provider_protocol(provider)
    if provider_type not in {"openai", "openai_compatible", "gemini", "claude", "deepseek", "doubao", "qwen", "custom"}:
        raise ModelConfigError(f"Unsupported provider_type: {provider_type}")
    if protocol not in {"openai", "openai_compatible", "gemini", "claude", "deepseek", "doubao", "qwen"}:
        raise ModelConfigError(f"Unsupported provider protocol: {protocol}")

    base_url = str(provider.get("base_url", "")).rstrip("/")
    if not base_url:
        raise ModelConfigError("Provider base_url is required")

    headers = {"Content-Type": "application/json"}
    meta = _provider_meta(provider)
    for key, value in _provider_extra_headers(provider).items():
        if str(key) == "__meta__":
            continue
        headers[str(key)] = str(value)
    secret_ref = provider.get("api_key_secret_ref")
    api_key = _resolve_secret(secret_ref) if secret_ref else ""
    if provider.get("organization_id"):
        headers["OpenAI-Organization"] = str(provider["organization_id"])

    resolved_system_prompt = str(system_prompt or model_record.get("default_system_prompt", ""))
    upstream_model_code = str(model_record.get("upstream_model_code") or model_record.get("model_code") or "").strip()
    if not upstream_model_code:
        raise ModelConfigError("Model upstream_model_code is required")

    temperature = float(_model_option(model_record, "temperature", 0.3))
    top_p = float(_model_option(model_record, "top_p", 1.0))
    max_tokens = int(_model_option(model_record, "max_tokens", 1024))
    timeout = float(_model_option(model_record, "timeout_sec", 30))

    body: Dict[str, Any]
    request_url: str
    if protocol in {"openai", "openai_compatible", "deepseek", "qwen"}:
        auth_header = str(meta.get("auth_header", "Authorization"))
        auth_scheme = str(meta.get("auth_scheme", "Bearer")).strip()
        if api_key:
            headers[auth_header] = api_key if auth_scheme.lower() == "raw" else f"{auth_scheme} {api_key}".strip()
        body = {
            "model": upstream_model_code,
            "messages": [
                {"role": "system", "content": resolved_system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "temperature": temperature,
            "top_p": top_p,
            "max_tokens": max_tokens,
        }
        if response_format is not None:
            body["response_format"] = response_format
        request_url = _join_url(base_url, str(meta.get("chat_path", "/chat/completions")))
    elif protocol == "doubao":
        auth_header = str(meta.get("auth_header", "Authorization"))
        auth_scheme = str(meta.get("auth_scheme", "Bearer")).strip()
        if api_key:
            headers[auth_header] = api_key if auth_scheme.lower() == "raw" else f"{auth_scheme} {api_key}".strip()
        body = {
            "model": upstream_model_code,
            "instructions": resolved_system_prompt,
            "input": [
                {
                    "role": "user",
                    "content": [{"type": "input_text", "text": user_prompt}],
                }
            ],
            "max_output_tokens": max_tokens,
        }
        if response_format is not None:
            body["text"] = {"format": response_format}
        request_url = _join_url(base_url, str(meta.get("chat_path", "/responses")))
    elif protocol == "claude":
        headers.pop("Authorization", None)
        headers[str(meta.get("auth_header", "x-api-key"))] = api_key
        headers["anthropic-version"] = str(meta.get("anthropic_version", "2023-06-01"))
        body = {
            "model": upstream_model_code,
            "system": resolved_system_prompt,
            "messages": [{"role": "user", "content": user_prompt}],
            "temperature": temperature,
            "max_tokens": max_tokens,
        }
        request_url = _join_url(base_url, str(meta.get("chat_path", "/messages")))
    else:
        headers.pop("Authorization", None)
        query_auth_name = str(meta.get("auth_query_name", "key"))
        body = {
            "system_instruction": {"parts": [{"text": resolved_system_prompt}]},
            "contents": [{"role": "user", "parts": [{"text": user_prompt}]}],
            "generationConfig": {
                "temperature": temperature,
                "topP": top_p,
                "maxOutputTokens": max_tokens,
            },
        }
        gemini_path = str(meta.get("chat_path", "/models/{model}:generateContent")).replace("{model}", quote(upstream_model_code, safe=""))
        request_url = f"{_join_url(base_url, gemini_path)}?{quote(query_auth_name, safe='')}={quote(api_key, safe='')}"

    async with httpx.AsyncClient(timeout=timeout) as client:
        log_headers = sanitize_dict(headers)
        logger.info(
            "Model API request providerType=%s protocol=%s model=%s url=%s timeoutSec=%.2f headers=%s payload=%s",
            provider_type,
            protocol,
            upstream_model_code,
            safe_url(request_url),
            timeout,
            log_headers,
            summarize_payload(body),
        )
        try:
            if stream_callback and response_format is None and protocol in {"openai", "openai_compatible", "deepseek", "qwen", "doubao"}:
                stream_body = body
                stream_url = request_url
                if protocol == "doubao":
                    stream_body = _build_openai_compatible_body(
                        upstream_model_code,
                        resolved_system_prompt,
                        user_prompt,
                        temperature,
                        top_p,
                        max_tokens,
                    )
                    stream_url = _join_url(base_url, str(meta.get("stream_chat_path", meta.get("chat_completions_path", "/chat/completions"))))
                stream_body["stream"] = True
                text = await _stream_openai_compatible(client, stream_url, headers, stream_body, stream_callback)
                logger.info(
                    "Model API stream completed providerType=%s protocol=%s model=%s durationMs=%.2f outputLength=%s",
                    provider_type,
                    protocol,
                    upstream_model_code,
                    duration_ms(start_time),
                    len(text),
                )
                return text
            response = await client.post(request_url, headers=headers, json=body)
            response.raise_for_status()
            payload = response.json()
        except Exception:
            logger.exception(
                "Model API request failed providerType=%s protocol=%s model=%s url=%s durationMs=%.2f",
                provider_type,
                protocol,
                upstream_model_code,
                safe_url(request_url),
                duration_ms(start_time),
            )
            raise
    logger.info(
        "Model API response providerType=%s protocol=%s model=%s status=%s durationMs=%.2f payload=%s",
        provider_type,
        protocol,
        upstream_model_code,
        response.status_code,
        duration_ms(start_time),
        summarize_payload(payload),
    )

    try:
        if protocol in {"openai", "openai_compatible", "deepseek", "qwen"}:
            return str(payload["choices"][0]["message"]["content"])
        if protocol == "doubao":
            output_text = _extract_doubao_text(payload)
            if output_text:
                return output_text
            raise KeyError("doubao output text not found")
        if protocol == "claude":
            return str(payload["content"][0]["text"])
        return str(payload["candidates"][0]["content"]["parts"][0]["text"])
    except Exception as exc:  # pragma: no cover - defensive
        raise ModelExecutionError(f"Invalid provider payload: {payload}") from exc


async def _stream_openai_compatible(
    client: httpx.AsyncClient,
    request_url: str,
    headers: Dict[str, str],
    body: Dict[str, Any],
    stream_callback: Callable[[str, bool], None],
) -> str:
    chunks: List[str] = []
    async with client.stream("POST", request_url, headers=headers, json=body) as response:
        response.raise_for_status()
        async for line in response.aiter_lines():
            if not line:
                continue
            text = line.strip()
            if not text.startswith("data:"):
                continue
            data = text[len("data:"):].strip()
            if data == "[DONE]":
                break
            try:
                payload = json.loads(data)
            except json.JSONDecodeError:
                continue
            delta = _extract_openai_stream_delta(payload)
            if not delta:
                continue
            chunks.append(delta)
            stream_callback(delta, False)
    stream_callback("", True)
    return "".join(chunks)


def _extract_openai_stream_delta(payload: Dict[str, Any]) -> str:
    try:
        choice = payload.get("choices", [{}])[0]
        delta = choice.get("delta") if isinstance(choice, dict) else {}
        if isinstance(delta, dict) and delta.get("content") is not None:
            return str(delta.get("content"))
        message = choice.get("message") if isinstance(choice, dict) else {}
        if isinstance(message, dict) and message.get("content") is not None:
            return str(message.get("content"))
    except Exception:
        return ""
    return ""


def _extract_doubao_text(payload: Dict[str, Any]) -> str | None:
    output_text = payload.get("output_text")
    if output_text is not None and str(output_text).strip():
        return str(output_text)

    output = payload.get("output")
    if not isinstance(output, list):
        return None

    parts: List[str] = []
    for item in output:
        if not isinstance(item, dict):
            continue
        if item.get("type") != "message" and item.get("role") != "assistant":
            continue
        content_items = item.get("content")
        if not isinstance(content_items, list):
            continue
        for content in content_items:
            if not isinstance(content, dict) or content.get("type") != "output_text":
                continue
            text = content.get("text")
            if text is not None and str(text).strip():
                parts.append(str(text))

    return "".join(parts) if parts else None


def _resolve_secret(secret_ref: Any) -> str:
    value = str(secret_ref)
    if value.startswith("env:"):
        env_name = value.split(":", 1)[1]
        env_value = os.getenv(env_name)
        if not env_value:
            raise ModelConfigError(f"Missing environment secret: {env_name}")
        return env_value
    return value
