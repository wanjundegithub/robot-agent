from unittest.mock import AsyncMock, Mock, patch

import pytest

from src.core.embedding_runtime import embed_texts_with_model


@pytest.mark.asyncio
async def test_embed_texts_with_openai_compatible_provider_posts_embeddings_payload():
    provider_configs = {
        "embedding-provider": {
            "provider_code": "embedding-provider",
            "provider_type": "openai_compatible",
            "base_url": "https://embedding.example.com/v1",
            "api_key_secret_ref": "test-secret",
            "extra_headers": {"__meta__": {"embedding_path": "/embeddings"}},
        }
    }
    model_records = {
        "embedding-qwen3-8b": {
            "model_code": "embedding-qwen3-8b",
            "provider_code": "embedding-provider",
            "upstream_model_code": "Qwen/Qwen3-Embedding-8B",
            "default_options": {"embedding_dimension": 3, "timeout_sec": 10},
        }
    }
    with patch("src.core.embedding_runtime.httpx.AsyncClient") as mock_client:
        response = Mock()
        response.raise_for_status.return_value = None
        response.json.return_value = {"data": [{"embedding": [0.1, 0.2, 0.3]}]}
        instance = AsyncMock()
        instance.__aenter__.return_value = instance
        instance.__aexit__.return_value = None
        instance.post.return_value = response
        mock_client.return_value = instance

        vectors = await embed_texts_with_model(
            texts=["hello"],
            model_code="embedding-qwen3-8b",
            provider_configs=provider_configs,
            model_records=model_records,
            expected_dimension=3,
        )

    assert vectors == [[0.1, 0.2, 0.3]]
    assert instance.post.call_args.args[0] == "https://embedding.example.com/v1/embeddings"
    assert instance.post.call_args.kwargs["json"]["model"] == "Qwen/Qwen3-Embedding-8B"
    assert instance.post.call_args.kwargs["json"]["input"] == ["hello"]


@pytest.mark.asyncio
async def test_embed_texts_with_modelscope_qwen_payload_uses_encoding_format_and_messages():
    provider_configs = {
        "modelscope-embedding": {
            "provider_code": "modelscope-embedding",
            "provider_type": "openai_compatible",
            "base_url": "https://api-inference.modelscope.cn/v1",
            "api_key_secret_ref": "test-secret",
            "extra_headers": {"__meta__": {"embedding_path": "/embeddings"}},
        }
    }
    model_records = {
        "embedding-qwen3-8b": {
            "model_code": "embedding-qwen3-8b",
            "provider_code": "modelscope-embedding",
            "upstream_model_code": "Qwen/Qwen3-Embedding-8B",
            "default_options": {
                "embedding_dimension": 4096,
                "timeout_sec": 10,
                "encoding_format": "float",
                "include_messages": True,
                "single_input_as_string": True,
            },
        }
    }
    with patch("src.core.embedding_runtime.httpx.AsyncClient") as mock_client:
        response = Mock()
        response.raise_for_status.return_value = None
        response.json.return_value = {"data": [{"embedding": [0.1] * 4096}]}
        instance = AsyncMock()
        instance.__aenter__.return_value = instance
        instance.__aexit__.return_value = None
        instance.post.return_value = response
        mock_client.return_value = instance

        vectors = await embed_texts_with_model(
            texts=["你好"],
            model_code="embedding-qwen3-8b",
            provider_configs=provider_configs,
            model_records=model_records,
            expected_dimension=4096,
        )

    assert len(vectors[0]) == 4096
    call_args = instance.post.call_args
    assert call_args.args[0] == "https://api-inference.modelscope.cn/v1/embeddings"
    assert call_args.kwargs["headers"]["Authorization"] == "Bearer test-secret"
    assert call_args.kwargs["json"] == {
        "model": "Qwen/Qwen3-Embedding-8B",
        "messages": [{"role": "user", "content": "你好"}],
        "input": "你好",
        "encoding_format": "float",
    }
