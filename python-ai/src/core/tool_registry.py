from __future__ import annotations

import hashlib
import json
from typing import Any, Dict

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
        failure_limit = int(config.get("simulate_failures", 0))
        failure_key = f"{tool_code}:{json.dumps(params, sort_keys=True, ensure_ascii=False)}"
        current_failures = self._failure_counts.get(failure_key, 0)
        if current_failures < failure_limit:
            self._failure_counts[failure_key] = current_failures + 1
            raise RetryableExecutionError("timeout", f"Simulated timeout for tool {tool_code}")

        if tool_code == "flight_search_api":
            flights = [
                {"flight_id": "MU5101", "departure_city": params.get("departure_city"), "arrival_city": params.get("arrival_city"), "departure_date": params.get("departure_date"), "price": 860},
                {"flight_id": "CA1831", "departure_city": params.get("departure_city"), "arrival_city": params.get("arrival_city"), "departure_date": params.get("departure_date"), "price": 920},
            ]
            return {"flight_options": flights, "summary": f"找到 {len(flights)} 个航班选项。"}

        if tool_code == "hotel_search_api":
            hotels = [
                {"hotel_id": "HTL1001", "city": params.get("arrival_city"), "name": "云栖酒店", "price_per_night": 480},
                {"hotel_id": "HTL1002", "city": params.get("arrival_city"), "name": "星河酒店", "price_per_night": 560},
            ]
            return {"hotel_options": hotels, "summary": f"找到 {len(hotels)} 家酒店。"}

        if tool_code == "seat_inventory_api":
            return {"available": True, "count": 6, "flight_id": params.get("flight_id")}

        raise RetryableExecutionError("validation_error", f"Unsupported tool: {tool_code}")

    def _tool_key(self, tool_code: str, params: Dict[str, Any]) -> str:
        serialized = json.dumps(params, sort_keys=True, ensure_ascii=False)
        digest = hashlib.md5(serialized.encode("utf-8")).hexdigest()
        return f"tool:{tool_code}:{digest}"


tool_registry = ToolExecutorRegistry()
