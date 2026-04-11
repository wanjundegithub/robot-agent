from __future__ import annotations

import hashlib
import json
import time
from collections import deque
from dataclasses import dataclass, field
from threading import Lock
from typing import Any, Deque, Dict, List


HIGH_RISK_TOOLS = {
    "delete_account",
    "transfer_money",
    "update_permission",
    "cancel_order",
}


class ProtectionError(Exception):
    def __init__(self, message: str, payload: Dict[str, Any] | None = None):
        super().__init__(message)
        self.payload = payload or {}


class RateLimitExceededError(ProtectionError):
    pass


class CircuitOpenError(ProtectionError):
    pass


class ConfirmationRequiredError(ProtectionError):
    pass


@dataclass
class VectorAccessPlan:
    kb_code: str
    kb_version: str
    shard_id: int
    cache_key: str


@dataclass
class WindowBucket:
    values: Deque[float] = field(default_factory=deque)


class SlidingWindowRateLimiter:
    def __init__(self) -> None:
        self._buckets: Dict[str, WindowBucket] = {}
        self._lock = Lock()

    def check(self, key: str, limit: int, window_seconds: int) -> Dict[str, Any]:
        now = time.time()
        with self._lock:
            bucket = self._buckets.setdefault(key, WindowBucket())
            threshold = now - window_seconds
            while bucket.values and bucket.values[0] <= threshold:
                bucket.values.popleft()
            if len(bucket.values) >= limit:
                retry_after = max(1, int(window_seconds - (now - bucket.values[0])))
                return {
                    "allowed": False,
                    "current": len(bucket.values),
                    "limit": limit,
                    "window_seconds": window_seconds,
                    "retry_after_seconds": retry_after,
                }
            bucket.values.append(now)
            return {
                "allowed": True,
                "current": len(bucket.values),
                "limit": limit,
                "window_seconds": window_seconds,
                "retry_after_seconds": 0,
            }

    def reset(self) -> None:
        with self._lock:
            self._buckets.clear()


class CircuitBreaker:
    def __init__(self, failure_threshold: int = 3, open_seconds: int = 30) -> None:
        self.failure_threshold = failure_threshold
        self.open_seconds = open_seconds
        self._states: Dict[str, Dict[str, Any]] = {}
        self._lock = Lock()

    def before_call(self, key: str) -> None:
        with self._lock:
            state = self._states.get(key)
            if not state:
                return
            opened_until = float(state.get("opened_until", 0.0))
            if opened_until > time.time():
                raise CircuitOpenError(
                    f"Circuit open for {key}",
                    {
                        "dependency": key,
                        "state": "open",
                        "opened_until": opened_until,
                    },
                )
            state["opened_until"] = 0.0
            state["failures"] = 0

    def record_success(self, key: str) -> None:
        with self._lock:
            self._states[key] = {"failures": 0, "opened_until": 0.0, "last_error": None}

    def record_failure(self, key: str, error: Exception) -> Dict[str, Any]:
        with self._lock:
            state = self._states.setdefault(key, {"failures": 0, "opened_until": 0.0, "last_error": None})
            state["failures"] = int(state.get("failures", 0)) + 1
            state["last_error"] = str(error)
            if state["failures"] >= self.failure_threshold:
                state["opened_until"] = time.time() + self.open_seconds
            return dict(state)

    def snapshot(self) -> Dict[str, Any]:
        with self._lock:
            return {
                key: {
                    "state": "open" if float(value.get("opened_until", 0.0)) > time.time() else "closed",
                    "failures": int(value.get("failures", 0)),
                    "last_error": value.get("last_error"),
                }
                for key, value in self._states.items()
            }

    def reset(self) -> None:
        with self._lock:
            self._states.clear()


class VectorAccessOptimizer:
    def __init__(self, shard_count: int = 4, cache_ttl_seconds: int = 120) -> None:
        self.shard_count = shard_count
        self.cache_ttl_seconds = cache_ttl_seconds
        self._cache: Dict[str, Dict[str, Any]] = {}
        self._lock = Lock()

    def plan(self, kb_code: str, kb_version: str, query: str) -> VectorAccessPlan:
        normalized = (query or "").strip().lower()
        digest = hashlib.md5(f"{kb_code}:{kb_version}:{normalized}".encode("utf-8")).hexdigest()
        shard_id = int(digest[:8], 16) % self.shard_count
        return VectorAccessPlan(
            kb_code=kb_code,
            kb_version=kb_version,
            shard_id=shard_id,
            cache_key=f"vector:{kb_code}:{kb_version}:{digest}",
        )

    def get_cached(self, cache_key: str) -> List[Dict[str, Any]] | None:
        now = time.time()
        with self._lock:
            cached = self._cache.get(cache_key)
            if not cached:
                return None
            if float(cached.get("expires_at", 0.0)) <= now:
                self._cache.pop(cache_key, None)
                return None
            return list(cached.get("documents", []))

    def put_cached(self, cache_key: str, documents: List[Dict[str, Any]]) -> None:
        with self._lock:
            self._cache[cache_key] = {
                "documents": list(documents),
                "expires_at": time.time() + self.cache_ttl_seconds,
            }

    def stats(self) -> Dict[str, Any]:
        with self._lock:
            return {
                "shard_count": self.shard_count,
                "cache_entries": len(self._cache),
                "cache_ttl_seconds": self.cache_ttl_seconds,
            }

    def reset(self) -> None:
        with self._lock:
            self._cache.clear()


class ToolConfirmationGate:
    def ensure_confirmed(self, context, tool_code: str, params: Dict[str, Any]) -> None:
        if tool_code not in HIGH_RISK_TOOLS:
            return
        confirmed_tool_codes = set(context.confirmed_tool_codes or [])
        if tool_code in confirmed_tool_codes:
            return
        raise ConfirmationRequiredError(
            f"Tool {tool_code} requires confirmation",
            {
                "tool_code": tool_code,
                "params_preview": self._mask_params(params),
                "status": "confirmation_required",
            },
        )

    def _mask_params(self, params: Dict[str, Any]) -> Dict[str, Any]:
        preview: Dict[str, Any] = {}
        for key, value in params.items():
            if value is None:
                preview[key] = None
                continue
            text = str(value)
            if len(text) > 6:
                preview[key] = text[:2] + "****" + text[-2:]
            else:
                preview[key] = "[MASKED]"
        return preview


class RuntimeProtectionManager:
    def __init__(self) -> None:
        self.rate_limiter = SlidingWindowRateLimiter()
        self.circuit_breaker = CircuitBreaker()

    def check_execution_start(self, context) -> None:
        checks = [
            ("user", f"user:{context.user_id}", 6, 60),
            ("session", f"session:{context.session_id}", 3, 30),
            (
                "workflow",
                f"workflow:{context.workflow_code}",
                10 if context.workflow_code == "general_query" else 4,
                60,
            ),
        ]
        for scope, key, limit, window_seconds in checks:
            result = self.rate_limiter.check(key, limit, window_seconds)
            context.add_runtime_metric(f"rate_limit_{scope}", result)
            if result["allowed"]:
                continue
            raise RateLimitExceededError(
                f"{scope} rate limit exceeded",
                {
                    "scope": scope,
                    "scope_id": key,
                    **result,
                },
            )

    def before_dependency(self, dependency_key: str) -> None:
        self.circuit_breaker.before_call(dependency_key)

    def record_dependency_success(self, dependency_key: str) -> None:
        self.circuit_breaker.record_success(dependency_key)

    def record_dependency_failure(self, dependency_key: str, error: Exception) -> Dict[str, Any]:
        return self.circuit_breaker.record_failure(dependency_key, error)

    def build_runtime_status(self) -> Dict[str, Any]:
        return {
            "rate_limits": [
                {"scope": "user", "limit": 6, "window_seconds": 60},
                {"scope": "session", "limit": 3, "window_seconds": 30},
                {"scope": "workflow", "limit": 4, "window_seconds": 60},
            ],
            "circuits": self.circuit_breaker.snapshot(),
            "vector_access": vector_access_optimizer.stats(),
        }

    def reset(self) -> None:
        self.rate_limiter.reset()
        self.circuit_breaker.reset()


runtime_protection_manager = RuntimeProtectionManager()
vector_access_optimizer = VectorAccessOptimizer()
tool_confirmation_gate = ToolConfirmationGate()
