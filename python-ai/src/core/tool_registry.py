from __future__ import annotations

import hashlib
import json
from typing import Any, Dict

import httpx

from .idempotency import get_idempotency_store
from .retry import RetryableExecutionError, execute_with_retry


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
                cached["cached"] = True
                return cached

        async def _run() -> Dict[str, Any]:
            result = await self._execute_once(tool_code, params, config)
            if idempotency_key is not None:
                get_idempotency_store().set_json(idempotency_key, result, int(config.get("cache_ttl", 3600)))
            return result

        return await execute_with_retry(retry_policy, _run)

    async def _execute_once(self, tool_code: str, params: Dict[str, Any], config: Dict[str, Any]) -> Dict[str, Any]:
        url = config.get("url")
        if not url:
            raise RetryableExecutionError("validation_error", f"Tool config missing url: {tool_code}")
        method = str(config.get("method", "POST")).upper()
        timeout = float(config.get("timeout", 15))
        headers = {str(key): str(value) for key, value in dict(config.get("headers", {})).items()}

        async with httpx.AsyncClient(timeout=timeout) as client:
            response = await client.request(
                method=method,
                url=str(url),
                json=params if method in {"POST", "PUT", "PATCH"} else None,
                params=params if method == "GET" else None,
                headers=headers,
            )
        if response.status_code >= 500:
            raise RetryableExecutionError("internal_error", f"Tool server error {response.status_code}: {response.text}")
        if response.status_code >= 400:
            raise RetryableExecutionError("validation_error", f"Tool call failed {response.status_code}: {response.text}")

        content_type = response.headers.get("content-type", "")
        if "application/json" in content_type:
            return response.json()
        return {"raw_response": response.text}

    def _tool_key(self, tool_code: str, params: Dict[str, Any]) -> str:
        serialized = json.dumps(params, sort_keys=True, ensure_ascii=False)
        digest = hashlib.md5(serialized.encode("utf-8")).hexdigest()
        return f"tool:{tool_code}:{digest}"


tool_registry = ToolExecutorRegistry()
