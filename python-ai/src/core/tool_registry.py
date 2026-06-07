from __future__ import annotations

import hashlib
import json
import logging
import re
import time
from typing import Any, Dict
from urllib.parse import quote

import httpx
from src.core.logging_utils import duration_ms, safe_url, sanitize_dict, summarize_payload

from .idempotency import get_idempotency_store
from .retry import RetryableExecutionError, execute_with_retry

logger = logging.getLogger(__name__)


class ToolExecutorRegistry:
    def __init__(self) -> None:
        self._failure_counts: Dict[str, int] = {}

    async def execute(self, tool_code: str, params: Dict[str, Any], config: Dict[str, Any]) -> Dict[str, Any]:
        is_idempotent = bool(config.get("idempotent", True))
        retry_policy = config.get("retry_policy", "network_timeout")
        idempotency_key = self._tool_key(tool_code, params) if is_idempotent else None

        if idempotency_key is not None:
            cached = get_idempotency_store().get_json(idempotency_key)
            if cached is not None:
                return self._normalize_cached_result(cached)

        async def _run() -> Dict[str, Any]:
            result = await self._execute_once(tool_code, params, config)
            if idempotency_key is not None:
                get_idempotency_store().set_json(idempotency_key, result, int(config.get("cache_ttl", 3600)))
            return result

        return await execute_with_retry(retry_policy, _run)

    async def _execute_once(self, tool_code: str, params: Dict[str, Any], config: Dict[str, Any]) -> Dict[str, Any]:
        start_time = time.perf_counter()
        invoke_type = str(config.get("invoke_type", "")).lower()
        if invoke_type == "function":
            function_name = str(config.get("function_name", "")).strip()
            return await self._execute_function(function_name, params)
        if invoke_type == "mcp":
            return await self._execute_json_endpoint(
                url=str(config.get("mcp_endpoint", "")),
                body={"tool_name": config.get("tool_name"), "arguments": params},
            )
        if invoke_type == "skill":
            return await self._execute_json_endpoint(
                url=str(config.get("skill_endpoint", "")),
                body={"skill_name": config.get("skill_name"), "inputs": params},
            )

        url = config.get("url")
        if not url:
            raise RetryableExecutionError("validation_error", f"Tool config missing url: {tool_code}")
        method = str(config.get("method", "POST")).upper()
        timeout = float(config.get("timeout", 15))
        headers = {str(key): str(value) for key, value in dict(config.get("headers", {})).items()}
        url, request_params = self._resolve_url_template(str(url), params)
        logger.info(
            "Tool request toolCode=%s invokeType=%s method=%s url=%s headers=%s payload=%s",
            tool_code,
            invoke_type or "http",
            method,
            safe_url(url),
            sanitize_dict(headers),
            summarize_payload(request_params),
        )

        async with httpx.AsyncClient(timeout=timeout) as client:
            response = await client.request(
                method=method,
                url=url,
                json=request_params if method in {"POST", "PUT", "PATCH"} else None,
                params=request_params if method == "GET" else None,
                headers=headers,
            )
        logger.info(
            "Tool response toolCode=%s invokeType=%s status=%s durationMs=%.2f payload=%s",
            tool_code,
            invoke_type or "http",
            response.status_code,
            duration_ms(start_time),
            summarize_payload(response.text),
        )
        if response.status_code >= 500:
            raise RetryableExecutionError("internal_error", f"Tool server error {response.status_code}: {response.text}")
        if response.status_code >= 400:
            raise RetryableExecutionError("validation_error", f"Tool call failed {response.status_code}: {response.text}")

        return self._parse_response_body(response)

    def _resolve_url_template(self, url: str, params: Dict[str, Any]) -> tuple[str, Dict[str, Any]]:
        if not isinstance(params, dict) or not params:
            return url, params

        consumed: set[str] = set()

        def replace(match: re.Match[str]) -> str:
            name = match.group(1)
            if name not in params:
                raise RetryableExecutionError("validation_error", f"Tool URL variable missing: {name}")
            consumed.add(name)
            return quote(str(params[name]), safe="")

        resolved_url = re.sub(r"\{([A-Za-z_][A-Za-z0-9_]*)\}", replace, url)
        if not consumed:
            return resolved_url, params

        return resolved_url, {key: value for key, value in params.items() if key not in consumed}

    async def _execute_json_endpoint(self, url: str, body: Dict[str, Any]) -> Dict[str, Any]:
        start_time = time.perf_counter()
        if not url:
            raise RetryableExecutionError("validation_error", "Tool endpoint is required")
        logger.info(
            "Tool endpoint request url=%s payload=%s",
            safe_url(url),
            summarize_payload(body),
        )
        async with httpx.AsyncClient(timeout=15) as client:
            response = await client.post(url, json=body)
        logger.info(
            "Tool endpoint response url=%s status=%s durationMs=%.2f payload=%s",
            safe_url(url),
            response.status_code,
            duration_ms(start_time),
            summarize_payload(response.text),
        )
        if response.status_code >= 500:
            raise RetryableExecutionError("internal_error", f"Tool server error {response.status_code}: {response.text}")
        if response.status_code >= 400:
            raise RetryableExecutionError("validation_error", f"Tool call failed {response.status_code}: {response.text}")
        return self._parse_response_body(response)

    def _parse_response_body(self, response: httpx.Response) -> Dict[str, Any]:
        content_type = response.headers.get("content-type", "")
        if "application/json" in content_type:
            return response.json()
        try:
            parsed = json.loads(response.text)
        except (TypeError, json.JSONDecodeError):
            return {"raw_response": response.text}
        if isinstance(parsed, dict):
            return parsed
        return {"raw_response": response.text}

    def _normalize_cached_result(self, cached: Dict[str, Any]) -> Dict[str, Any]:
        raw_response = cached.get("raw_response")
        if isinstance(raw_response, str):
            try:
                parsed = json.loads(raw_response)
            except json.JSONDecodeError:
                parsed = None
            if isinstance(parsed, dict):
                return {**parsed, "cached": True}
        return {**cached, "cached": True}

    async def _execute_function(self, function_name: str, params: Dict[str, Any]) -> Dict[str, Any]:
        if function_name == "merge_variables":
            return {
                "result": params,
                "summary": "已合并变量。",
            }
        if function_name == "extract_slots_summary":
            filtered = {key: value for key, value in params.items() if value not in (None, "", [])}
            return {
                "result": filtered,
                "summary": f"已提取 {len(filtered)} 个变量。",
            }
        raise RetryableExecutionError("validation_error", f"Unsupported function tool: {function_name}")

    def _tool_key(self, tool_code: str, params: Dict[str, Any]) -> str:
        serialized = json.dumps(params, sort_keys=True, ensure_ascii=False)
        digest = hashlib.md5(serialized.encode("utf-8")).hexdigest()
        return f"tool:{tool_code}:{digest}"


tool_registry = ToolExecutorRegistry()
