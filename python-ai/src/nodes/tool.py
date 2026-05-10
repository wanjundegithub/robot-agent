from typing import Dict, Any
import logging
import httpx
from src.core.protection import runtime_protection_manager, tool_confirmation_gate
from src.core.tool_registry import tool_registry
from src.core.logging_utils import duration_ms, safe_url, sanitize_dict, summarize_payload
from .base import BaseNode

logger = logging.getLogger(__name__)


class ToolNode(BaseNode):
    """工具调用节点"""

    def __init__(self, node_id: str, data: Dict[str, Any]):
        super().__init__(node_id, "tool")
        config = data.get("config", {})
        self.tool_config = config.get("tool", data.get("tool", {}))
        if not self.tool_config and (config.get("tool_code") or config.get("invoke_type")):
            self.tool_config = config
        self.tool_code = self.tool_config.get("tool_code", "")
        self.invoke_type = self.tool_config.get("invoke_type", "")
        self.method = self.tool_config.get("method", "GET").upper()
        self.url = self.tool_config.get("url", "")
        self.headers = self.tool_config.get("headers", {})
        self.timeout = self.tool_config.get("timeout", 30)
        self.params = self.tool_config.get("params", {})
        self.body = self.tool_config.get("body", {})

    async def execute(self, context) -> Dict[str, Any]:
        if self.invoke_type == "capability":
            raise RuntimeError("capability invoke_type must be resolved by Java before execution")

        if self.tool_code or self.invoke_type in {"function", "mcp", "skill"}:
            params = self._build_tool_params(context)
            resolved_tool_code = self.tool_code or self._derive_tool_code()
            capability_metadata = self._build_capability_metadata()
            tool_confirmation_gate.ensure_confirmed(context, resolved_tool_code, params)
            dependency_key = f"tool:{resolved_tool_code}"
            try:
                runtime_protection_manager.before_dependency(dependency_key)
                result = await tool_registry.execute(resolved_tool_code, params, self.tool_config)
                runtime_protection_manager.record_dependency_success(dependency_key)
                output = self._build_tool_output(result)
                context.add_execution_variables(output)
                return self.prepare_output({
                    "status": "completed",
                    "output": output,
                    "message_deltas": [result.get("summary")] if result.get("summary") else [],
                    "tool_called": {"tool_code": resolved_tool_code, "params": params, **capability_metadata},
                    "tool_returned": {"tool_code": resolved_tool_code, "params": params, "output": result, **capability_metadata},
                    "metrics": {"cached": bool(result.get("cached", False))},
                })
            except Exception as exc:
                circuit_state = runtime_protection_manager.record_dependency_failure(dependency_key, exc)
                degraded_output = {
                    "tool_code": resolved_tool_code,
                    "tool_status": "degraded",
                    "fallback_message": "工具暂时不可用，已返回降级结果。",
                }
                context.add_execution_variables(degraded_output)
                return self.prepare_output({
                    "status": "completed",
                    "output": degraded_output,
                    "message_deltas": [degraded_output["fallback_message"]],
                    "tool_called": {"tool_code": resolved_tool_code, "params": params, **capability_metadata},
                    "tool_returned": {"tool_code": resolved_tool_code, "params": params, "output": degraded_output, "error": str(exc), **capability_metadata},
                    "metrics": {
                        "cached": False,
                        "degraded": True,
                        "degradation_reason": str(exc),
                    },
                    "protection_events": [
                        {
                            "event_type": "protection.degraded",
                            "data": {
                                "tool_code": resolved_tool_code,
                                "reason": str(exc),
                            },
                        },
                        {
                            "event_type": "protection.circuit_open",
                            "data": {
                                "tool_code": resolved_tool_code,
                                "state": "open" if float(circuit_state.get("opened_until", 0.0)) > 0 else "closed",
                                "failures": int(circuit_state.get("failures", 0)),
                            },
                        },
                    ],
                })

        request_params = await self._prepare_request(context)

        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                response = await self._make_request(client, request_params)
                result = await self._parse_response(response)
                return self.prepare_output(result)
        except Exception as e:
            result = {
                "completed": True,
                "error": str(e),
                "response": None
            }
            return self.prepare_output(result)

    async def _prepare_request(self, context) -> Dict[str, Any]:
        url = self._replace_variables(self.url, context)

        params = {}
        for key, value in self.params.items():
            if isinstance(value, dict) and "$ref" in value:
                var_name = value["$ref"]
                params[key] = context.get_variable(var_name)
            else:
                params[key] = value

        body = {}
        if self.method in ["POST", "PUT", "PATCH"]:
            for key, value in self.body.items():
                if isinstance(value, dict) and "$ref" in value:
                    var_name = value["$ref"]
                    body[key] = context.get_variable(var_name)
                else:
                    body[key] = value

        headers = {}
        for key, value in self.headers.items():
            if isinstance(value, dict) and "$ref" in value:
                var_name = value["$ref"]
                headers[key] = str(context.get_variable(var_name))
            else:
                headers[key] = str(value)

        return {
            "method": self.method,
            "url": url,
            "params": params,
            "json": body if body else None,
            "headers": headers
        }

    async def _make_request(self, client: httpx.AsyncClient, params: Dict[str, Any]):
        import time
        start = time.perf_counter()
        logger.info(
            "Node tool HTTP request method=%s url=%s params=%s headers=%s payload=%s",
            params["method"],
            safe_url(str(params["url"])),
            summarize_payload(params.get("params")),
            sanitize_dict(params.get("headers", {})),
            summarize_payload(params.get("json")),
        )
        if params["json"]:
            response = await client.request(
                method=params["method"],
                url=params["url"],
                params=params["params"],
                json=params["json"],
                headers=params["headers"]
            )
        else:
            response = await client.request(
                method=params["method"],
                url=params["url"],
                params=params["params"],
                headers=params["headers"]
            )
        logger.info(
            "Node tool HTTP response method=%s url=%s status=%s durationMs=%.2f",
            params["method"],
            safe_url(str(params["url"])),
            response.status_code,
            duration_ms(start),
        )
        return response

    async def _parse_response(self, response: httpx.Response) -> Dict[str, Any]:
        result = {
            "completed": True,
            "status_code": response.status_code,
            "response": None,
            "error": None
        }

        if 200 <= response.status_code < 300:
            content_type = response.headers.get("content-type", "")
            if "application/json" in content_type:
                try:
                    result["response"] = response.json()
                except Exception:
                    result["response"] = response.text
            else:
                result["response"] = response.text
        else:
            result["error"] = f"HTTP {response.status_code}: {response.text}"

        return result

    def _replace_variables(self, text: str, context) -> str:
        import re
        def replace_var(match):
            var_name = match.group(1)
            value = context.get_variable(var_name)
            return str(value) if value is not None else ""
        return re.sub(r'\$\{([^}]+)\}', replace_var, text)

    def _build_tool_params(self, context) -> Dict[str, Any]:
        if self.tool_code == "flight_search_api":
            return {
                "departure_city": context.get_variable("departure_city"),
                "arrival_city": context.get_variable("arrival_city"),
                "departure_date": context.get_variable("departure_date"),
                "passengers": context.get_variable("passengers", 1),
            }
        if self.tool_code == "hotel_search_api":
            return {
                "arrival_city": context.get_variable("arrival_city"),
                "departure_date": context.get_variable("departure_date"),
                "nights": context.get_variable("nights", 1),
            }
        if self.tool_code == "seat_inventory_api":
            flight_options = context.get_variable("flight_options", []) or []
            first_flight = flight_options[0] if flight_options else {}
            return {
                "flight_id": first_flight.get("flight_id"),
                "departure_date": context.get_variable("departure_date"),
            }
        payload_mapping = self.tool_config.get("payload_mapping", {})
        if isinstance(payload_mapping, dict) and payload_mapping:
            payload: Dict[str, Any] = {}
            for key, value in payload_mapping.items():
                if isinstance(value, str) and value.startswith("execution."):
                    payload[key] = context.get_variable(value[len("execution."):])
                else:
                    payload[key] = value
            return payload
        return dict(context.execution_variables)

    def _build_tool_output(self, result: Dict[str, Any]) -> Dict[str, Any]:
        if self.tool_code == "flight_search_api":
            return {"flight_options": result.get("flight_options", [])}
        if self.tool_code == "hotel_search_api":
            return {"hotel_options": result.get("hotel_options", [])}
        if self.tool_code == "seat_inventory_api":
            return {
                "seat_available": result.get("available"),
                "seat_count": result.get("count"),
            }
        if isinstance(result.get("result"), dict):
            return dict(result["result"])
        return result

    def _derive_tool_code(self) -> str:
        if self.invoke_type == "function":
            return str(self.tool_config.get("function_name", "function_tool"))
        if self.invoke_type == "mcp":
            return str(self.tool_config.get("tool_name", "mcp_tool"))
        if self.invoke_type == "skill":
            return str(self.tool_config.get("skill_name", "skill_tool"))
        return "tool"

    def _build_capability_metadata(self) -> Dict[str, Any]:
        metadata: Dict[str, Any] = {}
        for key in ("group_code", "group_snapshot_version", "capability_code", "capability_version", "capability_type"):
            value = self.tool_config.get(key)
            if value not in (None, ""):
                metadata[key] = value
        return metadata
