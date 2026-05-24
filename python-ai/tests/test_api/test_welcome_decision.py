from unittest.mock import AsyncMock, patch

import httpx
import pytest

from src.api.main import app
from src.api.models import WelcomeDecisionRequest


def test_welcome_decision_request_model_accepts_expected_fields():
    request = WelcomeDecisionRequest(
        session_id="session-1",
        workflow_code="hotel_booking",
        workflow_version="1.0.0",
        workflow_summary={"name": "酒店预订助手", "description": "帮助用户查询和预订酒店"},
        session_context={"trigger": "ws_bootstrap", "has_user_message": False},
        provider_configs=[],
        model_records=[],
        routing_model_code="general-chat-v1",
        system_prompts={"workflow_welcome": "配置中心欢迎决策提示词"},
    )

    assert request.session_id == "session-1"
    assert request.workflow_code == "hotel_booking"
    assert request.workflow_version == "1.0.0"
    assert request.system_prompts["workflow_welcome"] == "配置中心欢迎决策提示词"


@pytest.mark.asyncio
async def test_welcome_decision_endpoint_delegates_to_helper():
    expected = {
        "should_greet": True,
        "message": "您好，我是酒店预订助手，可以帮您查询城市、日期和房型信息。",
        "reason": "固定工作流首次打开，适合欢迎用户",
    }

    with patch("src.api.main.decide_workflow_welcome", new=AsyncMock(return_value=expected)) as mocked_helper:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
            response = await client.post(
                "/api/phase5/workflow-welcome/decide",
                json={
                    "session_id": "session-1",
                    "workflow_code": "hotel_booking",
                    "workflow_version": "1.0.0",
                    "workflow_summary": {
                        "name": "酒店预订助手",
                        "description": "帮助用户查询和预订酒店",
                        "entry_rule": {},
                        "coordinator_prompts": [],
                        "opening_messages": [],
                    },
                    "session_context": {
                        "trigger": "ws_bootstrap",
                        "has_user_message": False,
                    },
                    "provider_configs": [],
                    "model_records": [],
                    "routing_model_code": "general-chat-v1",
                    "system_prompts": {"workflow_welcome": "配置中心欢迎决策提示词"},
                },
            )

    assert response.status_code == 200
    assert response.json() == expected
    assert mocked_helper.await_count == 1
    assert mocked_helper.await_args.kwargs["system_prompts"] == {"workflow_welcome": "配置中心欢迎决策提示词"}
