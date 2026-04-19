import json
from unittest.mock import AsyncMock, Mock, patch

import pytest

from src.core.model_runtime import classify_intent_with_profile, execute_profile_completion


@pytest.mark.asyncio
async def test_execute_profile_completion_calls_openai_compatible_provider():
    provider_configs = {
        "openai-compatible-prod": {
            "provider_code": "openai-compatible-prod",
            "provider_type": "openai_compatible",
            "base_url": "https://llm.example.com/v1",
        }
    }
    model_profiles = {
        "general-chat-v1": {
            "profile_code": "general-chat-v1",
            "provider_code": "openai-compatible-prod",
            "model_code": "qwen-plus",
            "temperature": 0.2,
            "top_p": 0.9,
            "max_tokens": 256,
            "timeout_sec": 10,
        }
    }

    with patch("src.core.model_runtime.httpx.AsyncClient") as mock_client:
        mock_response = Mock()
        mock_response.json.return_value = {
            "choices": [{"message": {"content": "结构化输出"}}]
        }
        mock_response.raise_for_status.return_value = None

        mock_instance = AsyncMock()
        mock_instance.__aenter__.return_value = mock_instance
        mock_instance.__aexit__.return_value = None
        mock_instance.post.return_value = mock_response
        mock_client.return_value = mock_instance

        result = await execute_profile_completion(
            profile_code="general-chat-v1",
            provider_configs=provider_configs,
            model_profiles=model_profiles,
            system_prompt="system",
            user_prompt="user",
        )

    assert result == "结构化输出"


@pytest.mark.asyncio
async def test_execute_profile_completion_supports_custom_claude_protocol():
    provider_configs = {
        "custom-claude": {
            "provider_code": "custom-claude",
            "provider_type": "custom",
            "base_url": "https://proxy.example.com/anthropic",
            "api_key_secret_ref": "test-secret",
            "extra_headers": {
                "__meta__": {
                    "protocol": "claude",
                    "chat_path": "/messages",
                    "auth_header": "x-api-key",
                }
            },
        }
    }
    model_profiles = {
        "custom-claude-v1": {
            "profile_code": "custom-claude-v1",
            "provider_code": "custom-claude",
            "model_code": "claude-3-5-sonnet-latest",
            "temperature": 0.2,
            "max_tokens": 256,
            "timeout_sec": 10,
        }
    }

    with patch("src.core.model_runtime.httpx.AsyncClient") as mock_client:
        mock_response = Mock()
        mock_response.json.return_value = {
            "content": [{"text": "自定义 Claude 通了"}]
        }
        mock_response.raise_for_status.return_value = None

        mock_instance = AsyncMock()
        mock_instance.__aenter__.return_value = mock_instance
        mock_instance.__aexit__.return_value = None
        mock_instance.post.return_value = mock_response
        mock_client.return_value = mock_instance

        result = await execute_profile_completion(
            profile_code="custom-claude-v1",
            provider_configs=provider_configs,
            model_profiles=model_profiles,
            system_prompt="system",
            user_prompt="user",
        )

    assert result == "自定义 Claude 通了"
    call_args = mock_instance.post.call_args
    assert call_args.args[0] == "https://proxy.example.com/anthropic/messages"
    assert call_args.kwargs["headers"]["x-api-key"] == "test-secret"


@pytest.mark.asyncio
async def test_classify_intent_with_profile_parses_json_payload():
    provider_configs = {
        "openai-compatible-prod": {
            "provider_code": "openai-compatible-prod",
            "provider_type": "openai_compatible",
            "base_url": "https://llm.example.com/v1",
        }
    }
    model_profiles = {
        "intent-router-v1": {
            "profile_code": "intent-router-v1",
            "provider_code": "openai-compatible-prod",
            "model_code": "qwen-plus",
            "temperature": 0.1,
            "top_p": 0.8,
            "max_tokens": 256,
            "timeout_sec": 10,
        }
    }

    with patch("src.core.model_runtime.httpx.AsyncClient") as mock_client:
        mock_response = Mock()
        mock_response.json.return_value = {
            "choices": [{
                "message": {
                    "content": json.dumps({
                        "intent_code": "book_flight",
                        "workflow_code": "flight_booking",
                        "confidence": 0.92,
                        "reason": "matched travel request",
                    }, ensure_ascii=False)
                }
            }]
        }
        mock_response.raise_for_status.return_value = None

        mock_instance = AsyncMock()
        mock_instance.__aenter__.return_value = mock_instance
        mock_instance.__aexit__.return_value = None
        mock_instance.post.return_value = mock_response
        mock_client.return_value = mock_instance

        result = await classify_intent_with_profile(
            message="我要订机票",
            candidate_workflows=[{"workflow_code": "flight_booking"}],
            intent_profile_code="intent-router-v1",
            provider_configs=provider_configs,
            model_profiles=model_profiles,
        )

    assert result["workflow_code"] == "flight_booking"
    assert result["intent_code"] == "book_flight"
