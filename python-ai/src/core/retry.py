from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Awaitable, Callable, Dict, Iterable, Optional, TypeVar


T = TypeVar("T")


@dataclass
class RetryPolicy:
    max_retries: int
    backoff: str
    initial_delay_ms: int
    max_delay_ms: int
    retryable_errors: Iterable[str]


RETRY_POLICIES: Dict[str, RetryPolicy] = {
    "network_timeout": RetryPolicy(
        max_retries=3,
        backoff="exponential",
        initial_delay_ms=200,
        max_delay_ms=1200,
        retryable_errors=("timeout", "connection_error", "dns_error"),
    ),
    "rate_limit": RetryPolicy(
        max_retries=2,
        backoff="fixed",
        initial_delay_ms=400,
        max_delay_ms=400,
        retryable_errors=("rate_limit_exceeded",),
    ),
    "llm_service_error": RetryPolicy(
        max_retries=2,
        backoff="exponential",
        initial_delay_ms=300,
        max_delay_ms=1000,
        retryable_errors=("internal_error", "service_unavailable"),
    ),
    "validation_error": RetryPolicy(
        max_retries=0,
        backoff="fixed",
        initial_delay_ms=0,
        max_delay_ms=0,
        retryable_errors=(),
    ),
}


class RetryableExecutionError(Exception):
    def __init__(self, error_type: str, message: str):
        super().__init__(message)
        self.error_type = error_type


async def execute_with_retry(
    policy_name: str,
    func: Callable[[], Awaitable[T]],
    on_retry: Optional[Callable[[int, int], Awaitable[None]]] = None,
) -> T:
    policy = RETRY_POLICIES.get(policy_name, RETRY_POLICIES["network_timeout"])
    last_error: Exception | None = None

    for attempt in range(policy.max_retries + 1):
        try:
            return await func()
        except RetryableExecutionError as error:
            last_error = error
            if error.error_type not in set(policy.retryable_errors):
                raise
            if attempt >= policy.max_retries:
                raise

            delay_ms = policy.initial_delay_ms
            if policy.backoff == "exponential":
                delay_ms = min(policy.initial_delay_ms * (2 ** attempt), policy.max_delay_ms)
            if on_retry is not None:
                await on_retry(attempt + 1, delay_ms)
            await asyncio.sleep(delay_ms / 1000)

    if last_error is not None:
        raise last_error
    raise RuntimeError("Retry policy exited unexpectedly")
