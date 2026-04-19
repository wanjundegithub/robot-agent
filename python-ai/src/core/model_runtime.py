from __future__ import annotations

import json
import os
from urllib.parse import quote
from typing import Any, Dict, List

import httpx


class ModelConfigError(Exception):
    pass


class ModelExecutionError(Exception):
    pass


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


def resolve_profile(model_profiles: Dict[str, Dict[str, Any]], profile_code: str) -> Dict[str, Any]:
    profile = model_profiles.get(profile_code)
    if not profile:
        raise ModelConfigError(f"Model profile not found: {profile_code}")
    return profile


async def classify_intent_with_profile(
    message: str,
    candidate_workflows: List[Dict[str, Any]],
    intent_profile_code: str,
    provider_configs: Dict[str, Dict[str, Any]],
    model_profiles: Dict[str, Dict[str, Any]],
) -> Dict[str, Any]:
    prompt = {
        "task": "intent_routing",
        "message": message,
        "candidate_workflows": candidate_workflows,
        "required_fields": ["intent_code", "workflow_code", "confidence", "reason"],
    }
    content = await execute_profile_completion(
        profile_code=intent_profile_code,
        provider_configs=provider_configs,
        model_profiles=model_profiles,
        system_prompt="你是服务机器人路由引擎，只能从候选工作流中选择最合适的 workflow_code，并返回 JSON。",
        user_prompt=json.dumps(prompt, ensure_ascii=False),
        response_format={"type": "json_object"},
    )
    try:
        parsed = json.loads(content)
    except json.JSONDecodeError as exc:
        raise ModelExecutionError(f"Intent classification returned invalid JSON: {content}") from exc
    if "workflow_code" not in parsed:
        raise ModelExecutionError("Intent classification missing workflow_code")
    return parsed


async def execute_profile_completion(
    profile_code: str,
    provider_configs: Dict[str, Dict[str, Any]],
    model_profiles: Dict[str, Dict[str, Any]],
    system_prompt: str,
    user_prompt: str,
    response_format: Dict[str, Any] | None = None,
) -> str:
    profile = resolve_profile(model_profiles, profile_code)
    provider = resolve_provider(provider_configs, str(profile.get("provider_code")))
    return await _invoke_provider(provider, profile, system_prompt, user_prompt, response_format)


async def _invoke_provider(
    provider: Dict[str, Any],
    profile: Dict[str, Any],
    system_prompt: str,
    user_prompt: str,
    response_format: Dict[str, Any] | None = None,
) -> str:
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

    body: Dict[str, Any]
    request_url: str
    model_code = str(profile.get("model_code"))
    if protocol in {"openai", "openai_compatible", "deepseek", "qwen"}:
        auth_header = str(meta.get("auth_header", "Authorization"))
        auth_scheme = str(meta.get("auth_scheme", "Bearer")).strip()
        if api_key:
            headers[auth_header] = api_key if auth_scheme.lower() == "raw" else f"{auth_scheme} {api_key}".strip()
        body = {
            "model": model_code,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "temperature": float(profile.get("temperature", 0.3)),
            "top_p": float(profile.get("top_p", 1.0)),
            "max_tokens": int(profile.get("max_tokens", 1024)),
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
            "model": model_code,
            "instructions": system_prompt,
            "input": [
                {
                    "role": "user",
                    "content": [{"type": "input_text", "text": user_prompt}],
                }
            ],
            "max_output_tokens": int(profile.get("max_tokens", 1024)),
        }
        if response_format is not None:
            body["text"] = {"format": response_format}
        request_url = _join_url(base_url, str(meta.get("chat_path", "/responses")))
    elif protocol == "claude":
        headers.pop("Authorization", None)
        headers[str(meta.get("auth_header", "x-api-key"))] = api_key
        headers["anthropic-version"] = str(meta.get("anthropic_version", "2023-06-01"))
        body = {
            "model": model_code,
            "system": system_prompt,
            "messages": [{"role": "user", "content": user_prompt}],
            "temperature": float(profile.get("temperature", 0.3)),
            "max_tokens": int(profile.get("max_tokens", 1024)),
        }
        request_url = _join_url(base_url, str(meta.get("chat_path", "/messages")))
    else:
        headers.pop("Authorization", None)
        query_auth_name = str(meta.get("auth_query_name", "key"))
        body = {
            "system_instruction": {"parts": [{"text": system_prompt}]},
            "contents": [{"role": "user", "parts": [{"text": user_prompt}]}],
            "generationConfig": {
                "temperature": float(profile.get("temperature", 0.3)),
                "topP": float(profile.get("top_p", 1.0)),
                "maxOutputTokens": int(profile.get("max_tokens", 1024)),
            },
        }
        gemini_path = str(meta.get("chat_path", "/models/{model}:generateContent")).replace("{model}", quote(model_code, safe=""))
        request_url = f"{_join_url(base_url, gemini_path)}?{quote(query_auth_name, safe='')}={quote(api_key, safe='')}"

    timeout = float(profile.get("timeout_sec", 30))
    async with httpx.AsyncClient(timeout=timeout) as client:
        response = await client.post(request_url, headers=headers, json=body)
        response.raise_for_status()
        payload = response.json()

    try:
        if protocol in {"openai", "openai_compatible", "deepseek", "qwen"}:
            return str(payload["choices"][0]["message"]["content"])
        if protocol == "doubao":
            for item in payload.get("output", []):
                if not isinstance(item, dict):
                    continue
                for content in item.get("content", []):
                    if isinstance(content, dict) and content.get("text") is not None:
                        return str(content["text"])
            raise KeyError("doubao output text not found")
        if protocol == "claude":
            return str(payload["content"][0]["text"])
        return str(payload["candidates"][0]["content"]["parts"][0]["text"])
    except Exception as exc:  # pragma: no cover - defensive
        raise ModelExecutionError(f"Invalid provider payload: {payload}") from exc


def _resolve_secret(secret_ref: Any) -> str:
    value = str(secret_ref)
    if value.startswith("env:"):
        env_name = value.split(":", 1)[1]
        env_value = os.getenv(env_name)
        if not env_value:
            raise ModelConfigError(f"Missing environment secret: {env_name}")
        return env_value
    return value
