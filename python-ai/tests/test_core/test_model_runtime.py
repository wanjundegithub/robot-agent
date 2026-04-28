import json
from unittest.mock import AsyncMock, Mock, patch

import pytest

from src.core.model_runtime import classify_intent_with_model_code, execute_model_completion


@pytest.mark.asyncio
async def test_execute_model_completion_calls_openai_compatible_provider():
    provider_configs = {
        "openai-compatible-prod": {
            "provider_code": "openai-compatible-prod",
            "provider_type": "openai_compatible",
            "base_url": "https://llm.example.com/v1",
        }
    }
    model_records = {
        "general-chat-v1": {
            "model_code": "general-chat-v1",
            "provider_code": "openai-compatible-prod",
            "upstream_model_code": "qwen-plus",
            "default_system_prompt": "你是测试助手。",
            "default_options": {
                "temperature": 0.2,
                "top_p": 0.9,
                "max_tokens": 256,
                "timeout_sec": 10,
            },
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

        result = await execute_model_completion(
            model_code="general-chat-v1",
            provider_configs=provider_configs,
            model_records=model_records,
            system_prompt=None,
            user_prompt="user",
        )

    assert result == "结构化输出"
    body = mock_instance.post.call_args.kwargs["json"]
    assert body["model"] == "qwen-plus"
    assert body["messages"][0]["content"] == "你是测试助手。"
    assert body["temperature"] == 0.2
    assert body["top_p"] == 0.9
    assert body["max_tokens"] == 256


@pytest.mark.asyncio
async def test_execute_model_completion_supports_custom_claude_protocol():
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
    model_records = {
        "custom-claude-v1": {
            "model_code": "custom-claude-v1",
            "provider_code": "custom-claude",
            "upstream_model_code": "claude-3-5-sonnet-latest",
            "default_options": {
                "temperature": 0.2,
                "max_tokens": 256,
                "timeout_sec": 10,
            },
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

        result = await execute_model_completion(
            model_code="custom-claude-v1",
            provider_configs=provider_configs,
            model_records=model_records,
            system_prompt="system",
            user_prompt="user",
        )

    assert result == "自定义 Claude 通了"
    call_args = mock_instance.post.call_args
    assert call_args.args[0] == "https://proxy.example.com/anthropic/messages"
    assert call_args.kwargs["headers"]["x-api-key"] == "test-secret"


@pytest.mark.asyncio
async def test_execute_model_completion_extracts_doubao_responses_output_text():
    provider_configs = {
        "doubao-prod": {
            "provider_code": "doubao-prod",
            "provider_type": "doubao",
            "base_url": "https://ark.cn-beijing.volces.com/api/v3",
            "api_key_secret_ref": "test-secret",
        }
    }
    model_records = {
        "doubao-chat": {
            "model_code": "doubao-chat",
            "provider_code": "doubao-prod",
            "upstream_model_code": "doubao-seed-2-0-pro-260215",
            "default_options": {
                "max_tokens": 256,
                "timeout_sec": 10,
            },
        }
    }

    with patch("src.core.model_runtime.httpx.AsyncClient") as mock_client:
        mock_response = Mock()
        mock_response.json.return_value = {
            "object": "response",
            "output": [
                {
                    "type": "reasoning",
                    "summary": [{"type": "summary_text", "text": "推理摘要不应作为最终回答"}],
                    "status": "completed",
                },
                {
                    "type": "message",
                    "role": "assistant",
                    "content": [
                        {"type": "output_text", "text": "在常规的十进制算术运算里，1+1=2。"}
                    ],
                    "status": "completed",
                },
            ],
            "usage": {"input_tokens": 55, "output_tokens": 434, "total_tokens": 489},
        }
        mock_response.raise_for_status.return_value = None

        mock_instance = AsyncMock()
        mock_instance.__aenter__.return_value = mock_instance
        mock_instance.__aexit__.return_value = None
        mock_instance.post.return_value = mock_response
        mock_client.return_value = mock_instance

        result = await execute_model_completion(
            model_code="doubao-chat",
            provider_configs=provider_configs,
            model_records=model_records,
            system_prompt=None,
            user_prompt="1+1等于几？",
        )

    assert result == "在常规的十进制算术运算里，1+1=2。"
    call_args = mock_instance.post.call_args
    assert call_args.args[0] == "https://ark.cn-beijing.volces.com/api/v3/responses"
    assert call_args.kwargs["headers"]["Authorization"] == "Bearer test-secret"


@pytest.mark.asyncio
async def test_classify_intent_with_model_code_parses_json_payload():
    provider_configs = {
        "openai-compatible-prod": {
            "provider_code": "openai-compatible-prod",
            "provider_type": "openai_compatible",
            "base_url": "https://llm.example.com/v1",
        }
    }
    model_records = {
        "intent-router-v1": {
            "model_code": "intent-router-v1",
            "provider_code": "openai-compatible-prod",
            "upstream_model_code": "qwen-plus",
            "default_options": {
                "temperature": 0.1,
                "top_p": 0.8,
                "max_tokens": 256,
                "timeout_sec": 10,
            },
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

        result = await classify_intent_with_model_code(
            message="我要订机票",
            candidate_workflows=[{"workflow_code": "flight_booking"}],
            routing_model_code="intent-router-v1",
            provider_configs=provider_configs,
            model_records=model_records,
        )

    assert result["workflow_code"] == "flight_booking"
    assert result["intent_code"] == "book_flight"
