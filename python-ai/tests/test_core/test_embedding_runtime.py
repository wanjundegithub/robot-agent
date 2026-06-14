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
        "embedding-bge-m3": {
            "model_code": "embedding-bge-m3",
            "provider_code": "embedding-provider",
            "upstream_model_code": "bge-m3",
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
            texts=["保修期为一年"],
            model_code="embedding-bge-m3",
            provider_configs=provider_configs,
            model_records=model_records,
            expected_dimension=3,
        )

    assert vectors == [[0.1, 0.2, 0.3]]
    assert instance.post.call_args.args[0] == "https://embedding.example.com/v1/embeddings"
    assert instance.post.call_args.kwargs["json"]["model"] == "bge-m3"
    assert instance.post.call_args.kwargs["json"]["input"] == ["保修期为一年"]
