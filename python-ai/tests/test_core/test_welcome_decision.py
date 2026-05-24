import json
import logging
from unittest.mock import AsyncMock, patch

import pytest

from src.core.welcome_decision import decide_workflow_welcome


def _provider_configs():
    return {
        "openai-compatible-prod": {
            "provider_code": "openai-compatible-prod",
            "provider_type": "openai_compatible",
            "base_url": "https://llm.example.com/v1",
        }
    }


def _model_records(model_code: str = "welcome-model"):
    return {
        model_code: {
            "model_code": model_code,
            "provider_code": "openai-compatible-prod",
            "upstream_model_code": "qwen-plus",
        }
    }


@pytest.mark.asyncio
async def test_decide_workflow_welcome_returns_greeting_when_model_says_yes(caplog):
    mocked_completion = AsyncMock(
        return_value=json.dumps(
            {
                "should_greet": True,
                "message": "您好，我是酒店预订助手。",
                "reason": "首次打开固定工作流",
            },
            ensure_ascii=False,
        )
    )
    workflow_summary = {
        "name": "酒店预订助手",
        "description": "帮助用户查询和预订酒店",
        "entry_rule": {"type": "intent"},
        "coordinator_prompts": [{"id": "prompt-1"}, {"id": "prompt-2"}],
        "opening_messages": [{"text": "欢迎使用"}],
    }

    with patch("src.core.welcome_decision.execute_model_completion", new=mocked_completion), caplog.at_level(
        logging.INFO
    ):
        result = await decide_workflow_welcome(
            session_id="session-1",
            workflow_code="hotel_booking",
            workflow_version="1.0.0",
            workflow_summary=workflow_summary,
            session_context={"trigger": "ws_bootstrap", "has_user_message": False},
            provider_configs=_provider_configs(),
            model_records=_model_records(),
            routing_model_code="welcome-model",
        )

    assert result == {
        "should_greet": True,
        "message": "您好，我是酒店预订助手。",
        "reason": "首次打开固定工作流",
    }
    assert mocked_completion.await_count == 1
    kwargs = mocked_completion.await_args.kwargs
    assert "工作流欢迎语决策引擎" in kwargs["system_prompt"]
    prompt_payload = json.loads(kwargs["user_prompt"])
    assert prompt_payload["session_id"] == "session-1"
    assert prompt_payload["workflow_code"] == "hotel_booking"
    assert prompt_payload["workflow_summary"] == workflow_summary
    assert any("welcome.decision.request" in record.message for record in caplog.records)
    assert any("welcome.decision.model_result" in record.message for record in caplog.records)


@pytest.mark.asyncio
async def test_decide_workflow_welcome_uses_configured_system_prompt():
    mocked_completion = AsyncMock(
        return_value=json.dumps(
            {
                "should_greet": False,
                "message": "",
                "reason": "no configured opening",
            },
            ensure_ascii=False,
        )
    )

    with patch("src.core.welcome_decision.execute_model_completion", new=mocked_completion):
        await decide_workflow_welcome(
            session_id="session-1",
            workflow_code="flight_booking",
            workflow_version="1.0.0",
            workflow_summary={},
            session_context={"trigger": "ws_bootstrap", "has_user_message": False},
            provider_configs=_provider_configs(),
            model_records=_model_records(),
            routing_model_code="welcome-model",
            system_prompts={"workflow_welcome": "配置中心欢迎决策提示词"},
        )

    assert mocked_completion.await_args.kwargs["system_prompt"] == "配置中心欢迎决策提示词"


@pytest.mark.asyncio
async def test_decide_workflow_welcome_forces_no_greet_when_message_is_empty():
    mocked_completion = AsyncMock(
        return_value=json.dumps(
            {
                "should_greet": True,
                "message": "",
                "reason": "should not greet without a message",
            },
            ensure_ascii=False,
        )
    )

    with patch("src.core.welcome_decision.execute_model_completion", new=mocked_completion):
        result = await decide_workflow_welcome(
            session_id="session-2",
            workflow_code="hotel_booking",
            workflow_version="1.0.0",
            workflow_summary={"name": "酒店预订助手"},
            session_context={"trigger": "ws_bootstrap", "has_user_message": False},
            provider_configs=_provider_configs(),
            model_records=_model_records(),
            routing_model_code="welcome-model",
        )

    assert result == {
        "should_greet": False,
        "message": "",
        "reason": "empty_message",
    }


@pytest.mark.asyncio
async def test_decide_workflow_welcome_clears_message_when_model_declines():
    mocked_completion = AsyncMock(
        return_value=json.dumps(
            {
                "should_greet": False,
                "message": "您好，我会在需要时提供帮助。",
                "reason": "不需要欢迎",
            },
            ensure_ascii=False,
        )
    )

    with patch("src.core.welcome_decision.execute_model_completion", new=mocked_completion):
        result = await decide_workflow_welcome(
            session_id="session-3",
            workflow_code="hotel_booking",
            workflow_version="1.0.0",
            workflow_summary={"name": "酒店预订助手"},
            session_context={"trigger": "ws_bootstrap", "has_user_message": False},
            provider_configs=_provider_configs(),
            model_records=_model_records(),
            routing_model_code="welcome-model",
        )

    assert result == {
        "should_greet": False,
        "message": "",
        "reason": "不需要欢迎",
    }


@pytest.mark.asyncio
async def test_decide_workflow_welcome_falls_back_on_invalid_json(caplog):
    mocked_completion = AsyncMock(return_value="not-json")

    with patch("src.core.welcome_decision.execute_model_completion", new=mocked_completion), caplog.at_level(
        logging.INFO
    ):
        result = await decide_workflow_welcome(
            session_id="session-4",
            workflow_code="hotel_booking",
            workflow_version="1.0.0",
            workflow_summary={"name": "酒店预订助手"},
            session_context={"trigger": "ws_bootstrap", "has_user_message": False},
            provider_configs=_provider_configs(),
            model_records=_model_records(),
            routing_model_code="welcome-model",
        )

    assert result == {
        "should_greet": False,
        "message": "",
        "reason": "invalid_json",
    }
    assert any("welcome.decision.failed" in record.message for record in caplog.records)


@pytest.mark.asyncio
async def test_decide_workflow_welcome_falls_back_when_model_config_is_missing(caplog):
    with caplog.at_level(logging.INFO):
        result = await decide_workflow_welcome(
            session_id="session-5",
            workflow_code="hotel_booking",
            workflow_version="1.0.0",
            workflow_summary={"name": "酒店预订助手"},
            session_context={"trigger": "ws_bootstrap", "has_user_message": False},
            provider_configs={},
            model_records={},
            routing_model_code="missing-model",
        )

    assert result == {
        "should_greet": False,
        "message": "",
        "reason": "model_config_missing",
    }
    assert any("welcome.decision.failed" in record.message for record in caplog.records)


@pytest.mark.asyncio
async def test_decide_workflow_welcome_falls_back_when_model_raises_exception(caplog):
    mocked_completion = AsyncMock(side_effect=RuntimeError("provider timeout"))

    with patch("src.core.welcome_decision.execute_model_completion", new=mocked_completion), caplog.at_level(
        logging.INFO
    ):
        result = await decide_workflow_welcome(
            session_id="session-6",
            workflow_code="hotel_booking",
            workflow_version="1.0.0",
            workflow_summary={"name": "酒店预订助手"},
            session_context={"trigger": "ws_bootstrap", "has_user_message": False},
            provider_configs=_provider_configs(),
            model_records=_model_records(),
            routing_model_code="welcome-model",
        )

    assert result == {
        "should_greet": False,
        "message": "",
        "reason": "model_error",
    }
    assert any("welcome.decision.failed" in record.message for record in caplog.records)
