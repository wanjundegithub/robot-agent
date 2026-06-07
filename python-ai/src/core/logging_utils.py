from __future__ import annotations

import logging
import os
import sys
import time
from pathlib import Path
from typing import Any, Dict
from urllib.parse import parse_qsl, urlencode, urlparse, urlunparse

SENSITIVE_KEYS = {
    "authorization",
    "api_key",
    "apikey",
    "api-key",
    "key",
    "token",
    "access_token",
    "refresh_token",
    "password",
    "passwd",
    "secret",
    "x-api-key",
    "proxy-authorization",
    "set-cookie",
    "cookie",
}

MAX_VALUE_LENGTH = 200
SENSITIVE_TEXT_MARKERS = (
    "authorization",
    "api_key",
    "apikey",
    "api-key",
    "token",
    "access_token",
    "refresh_token",
    "password",
    "passwd",
    "secret",
    "x-api-key",
    "cookie",
)


def configure_logging() -> None:
    root = logging.getLogger()
    if any(getattr(handler, "_python_ai_file_handler", False) for handler in root.handlers):
        return

    root.setLevel(logging.INFO)
    formatter = logging.Formatter(
        fmt="%(asctime)s.%(msecs)03d %(levelname)-5s %(name)s %(thread)d:%(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    for handler in root.handlers:
        handler.setFormatter(formatter)

    if not any(getattr(handler, "_python_ai_console_handler", False) for handler in root.handlers):
        console_handler = logging.StreamHandler(sys.stdout)
        console_handler._python_ai_console_handler = True
        console_handler.setLevel(logging.INFO)
        console_handler.setFormatter(formatter)
        root.addHandler(console_handler)

    repo_root = Path(__file__).resolve().parents[3]
    configured_log_dir = os.getenv("ROBOT_LOG_DIR")
    log_dir = Path(configured_log_dir) if configured_log_dir else repo_root / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    file_handler = logging.FileHandler(log_dir / "python-ai.log", encoding="utf-8")
    file_handler._python_ai_file_handler = True
    file_handler.setLevel(logging.INFO)
    file_handler.setFormatter(formatter)
    root.addHandler(file_handler)


def safe_url(url: str) -> str:
    try:
        parsed = urlparse(url)
        query_items = []
        for key, value in parse_qsl(parsed.query, keep_blank_values=True):
            query_items.append((key, "***" if key.lower() in SENSITIVE_KEYS else value))
        return urlunparse((
            parsed.scheme,
            parsed.netloc,
            parsed.path,
            "",
            urlencode(query_items),
            "",
        ))
    except Exception:
        return _sanitize_text(url)


def _sanitize_text(value: str) -> str:
    text = value
    lower_text = text.lower()
    if any(marker in lower_text for marker in SENSITIVE_TEXT_MARKERS):
        return "<redacted-sensitive-text>"
    if len(text) > MAX_VALUE_LENGTH:
        return f"{text[:MAX_VALUE_LENGTH]}...(truncated)"
    return text


def _mask_value(value: Any) -> str:
    if value is None:
        return "null"
    text = str(value)
    if len(text) <= 8:
        return "***"
    return f"{text[:3]}***{text[-2:]}"


def sanitize_dict(data: Dict[str, Any] | None) -> Dict[str, Any]:
    if not isinstance(data, dict):
        return {}
    sanitized: Dict[str, Any] = {}
    for key, value in data.items():
        normalized_key = str(key).lower()
        if normalized_key in SENSITIVE_KEYS:
            sanitized[str(key)] = _mask_value(value)
            continue
        if isinstance(value, dict):
            sanitized[str(key)] = sanitize_dict(value)
            continue
        if isinstance(value, list):
            sanitized[str(key)] = f"<list:{len(value)}>"
            continue
        value_text = str(value)
        sanitized[str(key)] = _sanitize_text(value_text)
    return sanitized


def summarize_payload(payload: Any) -> str:
    if payload is None:
        return "none"
    if isinstance(payload, dict):
        return f"dict(keys={list(payload.keys())[:10]})"
    if isinstance(payload, list):
        return f"list(len={len(payload)})"
    text = str(payload)
    return _sanitize_text(text)


def duration_ms(start_time: float) -> float:
    return round((time.perf_counter() - start_time) * 1000, 2)
