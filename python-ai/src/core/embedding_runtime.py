from __future__ import annotations

from typing import Any, Dict, List

import httpx

from src.core.model_runtime import (
    ModelConfigError,
    _join_url,
    _provider_meta,
    _resolve_secret,
    resolve_model_record,
    resolve_provider,
)


async def embed_texts_with_model(
    texts: List[str],
    model_code: str,
    provider_configs: Dict[str, Dict[str, Any]],
    model_records: Dict[str, Dict[str, Any]],
    expected_dimension: int,
) -> List[List[float]]:
    if not texts:
        return []
    model_record = resolve_model_record(model_records, model_code)
    provider = resolve_provider(provider_configs, str(model_record.get("provider_code")))
    upstream_model_code = str(model_record.get("upstream_model_code") or model_record.get("model_code") or "").strip()
    if not upstream_model_code:
        raise ModelConfigError("Embedding model upstream_model_code is required")

    base_url = str(provider.get("base_url", "")).rstrip("/")
    if not base_url:
        raise ModelConfigError("Embedding provider base_url is required")

    meta = _provider_meta(provider)
    request_url = _join_url(base_url, str(meta.get("embedding_path", "/embeddings")))
    headers = {"Content-Type": "application/json"}
    secret_ref = provider.get("api_key_secret_ref")
    if secret_ref:
        auth_header = str(meta.get("auth_header", "Authorization"))
        auth_scheme = str(meta.get("auth_scheme", "Bearer")).strip()
        api_key = _resolve_secret(secret_ref)
        headers[auth_header] = api_key if auth_scheme.lower() == "raw" else f"{auth_scheme} {api_key}".strip()

    timeout = float(_default_options(model_record).get("timeout_sec", 30))
    body = {"model": upstream_model_code, "input": texts}
    async with httpx.AsyncClient(timeout=timeout) as client:
        response = await client.post(request_url, headers=headers, json=body)
        response.raise_for_status()
        payload = response.json()

    vectors = _extract_vectors(payload)
    for vector in vectors:
        if len(vector) != expected_dimension:
            raise ModelConfigError(f"Embedding dimension mismatch: expected {expected_dimension}, got {len(vector)}")
    return vectors


def _default_options(model_record: Dict[str, Any]) -> Dict[str, Any]:
    options = model_record.get("default_options")
    return options if isinstance(options, dict) else {}


def _extract_vectors(payload: Dict[str, Any]) -> List[List[float]]:
    data = payload.get("data")
    if not isinstance(data, list):
        raise ModelConfigError("Embedding response missing data list")
    vectors: List[List[float]] = []
    for item in data:
        if not isinstance(item, dict) or not isinstance(item.get("embedding"), list):
            raise ModelConfigError("Embedding response item missing embedding")
        vectors.append([float(value) for value in item["embedding"]])
    return vectors
