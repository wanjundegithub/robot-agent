from __future__ import annotations

import json
import time
from dataclasses import dataclass
from typing import Any, Dict, Optional, Protocol

import redis

from .settings import settings


class IdempotencyStore(Protocol):
    def get_json(self, key: str) -> Optional[Dict[str, Any]]:
        ...

    def set_json(self, key: str, value: Dict[str, Any], ttl_seconds: int) -> None:
        ...

    def exists(self, key: str) -> bool:
        ...


@dataclass
class StoredValue:
    expires_at: float
    value: str


class InMemoryIdempotencyStore:
    def __init__(self) -> None:
        self._values: Dict[str, StoredValue] = {}

    def get_json(self, key: str) -> Optional[Dict[str, Any]]:
        self._cleanup()
        item = self._values.get(key)
        if item is None:
            return None
        return json.loads(item.value)

    def set_json(self, key: str, value: Dict[str, Any], ttl_seconds: int) -> None:
        self._values[key] = StoredValue(
            expires_at=time.time() + ttl_seconds,
            value=json.dumps(value, ensure_ascii=False),
        )

    def exists(self, key: str) -> bool:
        self._cleanup()
        return key in self._values

    def _cleanup(self) -> None:
        now = time.time()
        expired_keys = [key for key, value in self._values.items() if value.expires_at <= now]
        for key in expired_keys:
            self._values.pop(key, None)


class RedisIdempotencyStore:
    def __init__(self, redis_url: str) -> None:
        self._client = redis.Redis.from_url(redis_url, decode_responses=True, socket_timeout=0.2)

    def ping(self) -> bool:
        try:
            return bool(self._client.ping())
        except redis.RedisError:
            return False

    def get_json(self, key: str) -> Optional[Dict[str, Any]]:
        value = self._client.get(key)
        return None if value is None else json.loads(value)

    def set_json(self, key: str, value: Dict[str, Any], ttl_seconds: int) -> None:
        self._client.setex(key, ttl_seconds, json.dumps(value, ensure_ascii=False))

    def exists(self, key: str) -> bool:
        return bool(self._client.exists(key))


_idempotency_store: IdempotencyStore = InMemoryIdempotencyStore()
_idempotency_backend = "memory"


def initialize_idempotency_store() -> IdempotencyStore:
    global _idempotency_store, _idempotency_backend
    if settings.redis_enabled:
        redis_store = RedisIdempotencyStore(settings.redis_url)
        if redis_store.ping():
            _idempotency_store = redis_store
            _idempotency_backend = "redis"
            return _idempotency_store
    _idempotency_store = InMemoryIdempotencyStore()
    _idempotency_backend = "memory"
    return _idempotency_store


def get_idempotency_store() -> IdempotencyStore:
    return _idempotency_store


def get_idempotency_backend() -> str:
    return _idempotency_backend
