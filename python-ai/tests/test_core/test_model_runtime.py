import json
from unittest.mock import AsyncMock, Mock, patch

import pytest

from src.core.model_runtime import (
    ModelExecutionError,
    classify_intent_with_model_code,
    execute_model_completion,
)


class _FakeLineIterator:
    def __init__(self, lines):
        self._lines = iter(lines)

    def __aiter__(self):
        return self

    async def __anext__(self):
        try:
            return next(self._lines)
        except StopIteration as exc:
            raise StopAsyncIteration from exc


class _FakeStreamResponse:
    def __init__(self, lines):
        self.lines = lines
        self.status_code = 200

    def raise_for_status(self):
        return None

    def aiter_lines(self):
        return _FakeLineIterator(self.lines)


class _FakeStreamContext:
    def __init__(self, response):
        self.response = response

    async def __aenter__(self):
        return self.response

    async def __aexit__(self, *_args):
        return None


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
            "default_system_prompt": "You are a test assistant.",
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
            "choices": [{"message": {"content": "structured output"}}]
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

    assert result == "structured output"
    body = mock_instance.post.call_args.kwargs["json"]
    assert body["model"] == "qwen-plus"
    assert body["messages"][0]["content"] == "You are a test assistant."
    assert body["temperature"] == 0.2
    assert body["top_p"] == 0.9
    assert body["max_tokens"] == 256


@pytest.mark.asyncio
async def test_execute_model_completion_passes_custom_body_options_for_structured_openai_compatible_call():
    provider_configs = {
        "modelscope-prod": {
            "provider_code": "modelscope-prod",
            "provider_type": "custom",
            "base_url": "https://api-inference.modelscope.cn/v1/chat/completions",
            "api_key_secret_ref": "test-secret",
        }
    }
    model_records = {
        "qwen3-json": {
            "model_code": "qwen3-json",
            "provider_code": "modelscope-prod",
            "upstream_model_code": "Qwen/Qwen3-8B",
            "default_options": {
                "stream": True,
                "enable_thinking": False,
                "max_tokens": 2048,
                "timeout_sec": 10,
            },
        }
    }

    with patch("src.core.model_runtime.httpx.AsyncClient") as mock_client:
        mock_response = Mock()
        mock_response.json.return_value = {
            "choices": [{"message": {"content": "{\"ok\":true}"}}]
        }
        mock_response.raise_for_status.return_value = None

        mock_instance = AsyncMock()
        mock_instance.__aenter__.return_value = mock_instance
        mock_instance.__aexit__.return_value = None
        mock_instance.post.return_value = mock_response
        mock_client.return_value = mock_instance

        result = await execute_model_completion(
            model_code="qwen3-json",
            provider_configs=provider_configs,
            model_records=model_records,
            system_prompt="Return JSON only.",
            user_prompt="ping",
            response_format={"type": "json_object"},
        )

    assert result == "{\"ok\":true}"
    body = mock_instance.post.call_args.kwargs["json"]
    assert body["max_tokens"] == 2048
    assert body["enable_thinking"] is False
    assert body["stream"] is False
    assert body["response_format"] == {"type": "json_object"}


@pytest.mark.asyncio
async def test_execute_model_completion_streams_openai_compatible_delta_chunks():
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
        }
    }
    streamed = []

    with patch("src.core.model_runtime.httpx.AsyncClient") as mock_client:
        stream_response = _FakeStreamResponse([
            'data: {"choices":[{"delta":{"content":"你好"}}]}',
            'data: {"choices":[{"delta":{"content":"，世界"}}]}',
            'data: [DONE]',
        ])
        mock_instance = AsyncMock()
        mock_instance.__aenter__.return_value = mock_instance
        mock_instance.__aexit__.return_value = None
        mock_instance.stream = Mock(return_value=_FakeStreamContext(stream_response))
        mock_client.return_value = mock_instance

        result = await execute_model_completion(
            model_code="general-chat-v1",
            provider_configs=provider_configs,
            model_records=model_records,
            system_prompt="system",
            user_prompt="user",
            stream_callback=lambda chunk, is_complete=False: streamed.append((chunk, is_complete)),
        )

    assert result == "你好，世界"
    assert streamed == [("你好", False), ("，世界", False), ("", True)]
    body = mock_instance.stream.call_args.kwargs["json"]
    assert body["stream"] is True


@pytest.mark.asyncio
async def test_execute_model_completion_streams_doubao_openai_compatible_delta_chunks():
    provider_configs = {
        "doubao-prod": {
            "provider_code": "doubao-prod",
            "provider_type": "doubao",
            "base_url": "https://ark.cn-beijing.volces.com/api/v3",
        }
    }
    model_records = {
        "doubao-chat": {
            "model_code": "doubao-chat",
            "provider_code": "doubao-prod",
            "upstream_model_code": "doubao-seed",
        }
    }
    streamed = []

    with patch("src.core.model_runtime.httpx.AsyncClient") as mock_client:
        stream_response = _FakeStreamResponse([
            'data: {"choices":[{"delta":{"content":"\u8c46\u5305"}}]}',
            'data: {"choices":[{"delta":{"content":"\u6d41\u5f0f"}}]}',
            'data: [DONE]',
        ])
        mock_instance = AsyncMock()
        mock_instance.__aenter__.return_value = mock_instance
        mock_instance.__aexit__.return_value = None
        mock_instance.stream = Mock(return_value=_FakeStreamContext(stream_response))
        mock_client.return_value = mock_instance

        result = await execute_model_completion(
            model_code="doubao-chat",
            provider_configs=provider_configs,
            model_records=model_records,
            system_prompt="system",
            user_prompt="user",
            stream_callback=lambda chunk, is_complete=False: streamed.append((chunk, is_complete)),
        )

    assert result == "\u8c46\u5305\u6d41\u5f0f"
    assert streamed == [("\u8c46\u5305", False), ("\u6d41\u5f0f", False), ("", True)]
    body = mock_instance.stream.call_args.kwargs["json"]
    assert body["stream"] is True
    mock_instance.post.assert_not_called()


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
            "content": [{"text": "Custom Claude response"}]
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

    assert result == "Custom Claude response"
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
                    "summary": [{"type": "summary_text", "text": "reasoning summary"}],
                    "status": "completed",
                },
                {
                    "type": "message",
                    "role": "assistant",
                    "content": [
                        {"type": "output_text", "text": "The answer is 2."}
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
            user_prompt="what is 1+1?",
        )

    assert result == "The answer is 2."
    call_args = mock_instance.post.call_args
    assert call_args.args[0] == "https://ark.cn-beijing.volces.com/api/v3/responses"
    assert call_args.kwargs["headers"]["Authorization"] == "Bearer test-secret"
    assert call_args.kwargs["json"]["max_output_tokens"] == 256


@pytest.mark.asyncio
async def test_execute_model_completion_uses_call_max_tokens_override_for_doubao():
    provider_configs = {
        "doubao-prod": {
            "provider_code": "doubao-prod",
            "provider_type": "doubao",
            "base_url": "https://ark.cn-beijing.volces.com/api/v3",
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
                    "type": "message",
                    "role": "assistant",
                    "content": [{"type": "output_text", "text": "ok"}],
                    "status": "completed",
                }
            ],
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
            user_prompt="hello",
            max_tokens=65535,
        )

    assert result == "ok"
    assert mock_instance.post.call_args.kwargs["json"]["max_output_tokens"] == 65535


@pytest.mark.asyncio
async def test_classify_intent_with_model_code_parses_structured_matched_payload():
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

    mocked_completion = AsyncMock(
        return_value=json.dumps(
            {
                "matched": True,
                "intent_code": "book_flight",
                "workflow_code": "flight_booking",
                "target_type": "workflow",
                "target_code": "flight_booking",
                "confidence": 1.5,
                "reason": "matched travel request",
                "need_clarification": False,
                "clarification_question": None,
            },
            ensure_ascii=False,
        )
    )

    with patch("src.core.model_runtime.execute_model_completion", new=mocked_completion):
        result = await classify_intent_with_model_code(
            message="book a flight",
            candidate_workflows=[
                {
                    "workflow_code": "flight_booking",
                    "target_type": "workflow",
                    "target_code": "flight_booking",
                    "confidence": 0.76,
                    "evidence": "travel request",
                }
            ],
            routing_model_code="intent-router-v1",
            provider_configs=provider_configs,
            model_records=model_records,
        )

    assert result["matched"] is True
    assert result["workflow_code"] == "flight_booking"
    assert result["intent_code"] == "book_flight"
    assert result["target_type"] == "workflow"
    assert result["target_code"] == "flight_booking"
    assert result["confidence"] == 1.0
    assert result["need_clarification"] is False


@pytest.mark.asyncio
async def test_classify_intent_with_model_code_normalizes_no_match_response():
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
        }
    }

    with patch(
        "src.core.model_runtime.execute_model_completion",
        new=AsyncMock(
            return_value=json.dumps(
                {
                    "matched": False,
                    "intent_code": "should_be_cleared",
                    "workflow_code": "should_be_cleared",
                    "target_type": "workflow",
                    "target_code": "should_be_cleared",
                    "confidence": -0.3,
                    "reason": "not sure",
                    "need_clarification": False,
                    "clarification_question": "",
                },
                ensure_ascii=False,
            )
        ),
    ):
        result = await classify_intent_with_model_code(
            message="help me",
            candidate_workflows=[{"workflow_code": "flight_booking"}],
            routing_model_code="intent-router-v1",
            provider_configs=provider_configs,
            model_records=model_records,
        )

    assert result["matched"] is False
    assert result["intent_code"] is None
    assert result["workflow_code"] is None
    assert result["target_type"] is None
    assert result["target_code"] is None
    assert result["confidence"] == 0.0
    assert result["need_clarification"] is True
    assert result["clarification_question"] == "请问您想办理哪类业务？"


@pytest.mark.asyncio
async def test_classify_intent_with_model_code_raises_when_matched_missing_workflow():
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
        }
    }

    with patch(
        "src.core.model_runtime.execute_model_completion",
        new=AsyncMock(
            return_value=json.dumps(
                {
                    "matched": True,
                    "intent_code": "book_flight",
                    "workflow_code": "",
                    "confidence": 0.8,
                },
                ensure_ascii=False,
            )
        ),
    ):
        with pytest.raises(ModelExecutionError, match="missing workflow_code"):
            await classify_intent_with_model_code(
                message="book a flight",
                candidate_workflows=[{"workflow_code": "flight_booking"}],
                routing_model_code="intent-router-v1",
                provider_configs=provider_configs,
                model_records=model_records,
            )


@pytest.mark.asyncio
async def test_classify_intent_with_model_code_raises_on_invalid_json():
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
        }
    }

    with patch(
        "src.core.model_runtime.execute_model_completion",
        new=AsyncMock(return_value="not-json"),
    ):
        with pytest.raises(ModelExecutionError, match="invalid JSON"):
            await classify_intent_with_model_code(
                message="query order",
                candidate_workflows=[{"workflow_code": "order_query"}],
                routing_model_code="intent-router-v1",
                provider_configs=provider_configs,
                model_records=model_records,
            )


@pytest.mark.asyncio
async def test_classify_intent_with_model_code_sends_prompt_contract():
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
        }
    }

    mocked_completion = AsyncMock(
        return_value=json.dumps(
            {
                "matched": False,
                "intent_code": None,
                "workflow_code": None,
                "target_type": None,
                "target_code": None,
                "confidence": 0.0,
                "reason": "unclear",
                "need_clarification": True,
                "clarification_question": "请问您想办理哪类业务？",
            },
            ensure_ascii=False,
        )
    )

    candidates = [
        {
            "workflow_code": "order_query",
            "target_type": "workflow",
            "target_code": "order_query",
            "confidence": 0.72,
            "evidence": "mentions order status",
        }
    ]

    with patch("src.core.model_runtime.execute_model_completion", new=mocked_completion):
        await classify_intent_with_model_code(
            message="where is my order",
            candidate_workflows=candidates,
            routing_model_code="intent-router-v1",
            provider_configs=provider_configs,
            model_records=model_records,
        )

    kwargs = mocked_completion.await_args.kwargs
    assert kwargs["max_tokens"] == 2048
    assert "choose workflow_code only from provided candidate_workflows" in kwargs["system_prompt"].lower()
    assert "matched=false" in kwargs["system_prompt"].lower()
    assert "generate clarification_question" in kwargs["system_prompt"].lower()
    prompt_payload = json.loads(kwargs["user_prompt"])
    assert prompt_payload["candidate_workflows"] == candidates
    assert prompt_payload["required_fields"] == [
        "matched",
        "intent_code",
        "workflow_code",
        "target_type",
        "target_code",
        "confidence",
        "reason",
        "need_clarification",
        "clarification_question",
    ]
    assert prompt_payload["fallback_message_requirements"]["field"] == "clarification_question"
    assert "matched=false" in prompt_payload["fallback_message_requirements"]["when"]


@pytest.mark.asyncio
async def test_classify_intent_with_model_code_uses_configured_system_prompt():
    mocked_completion = AsyncMock(
        return_value=json.dumps(
            {
                "matched": False,
                "confidence": 0.0,
                "reason": "unclear",
                "need_clarification": True,
                "clarification_question": "请问您想办理哪类业务？",
            },
            ensure_ascii=False,
        )
    )

    with patch("src.core.model_runtime.execute_model_completion", new=mocked_completion):
        await classify_intent_with_model_code(
            message="我要预定机票",
            candidate_workflows=[],
            routing_model_code="intent-router-v1",
            provider_configs={
                "demo": {
                    "provider_code": "demo",
                    "provider_type": "custom",
                    "api_key": "test",
                    "base_url": "https://example.com",
                }
            },
            model_records={"intent-router-v1": {"model_code": "intent-router-v1", "provider_code": "demo"}},
            system_prompts={"intent_routing": "配置中心意图路由提示词"},
        )

    assert mocked_completion.await_args.kwargs["system_prompt"] == "配置中心意图路由提示词"
