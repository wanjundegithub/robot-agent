import pytest
import src.core.react_decision as react_decision_module

from src.core.context import ExecutionContext
from src.core.react_decision import ReactDecisionService


@pytest.mark.asyncio
async def test_react_decision_routes_to_form_when_required_slots_missing():
    context = ExecutionContext(
        execution_id="exec-react-missing",
        session_id="session-react-missing",
        workflow_code="flight_booking",
        workflow_version="2.0.0",
    )
    context.add_execution_variables({"departure_city": "北京"})

    service = ReactDecisionService()
    decision = await service.decide_next_node(
        current_node={"id": "extract_slots", "type": "llm", "config": {}},
        result={"output": {"departure_city": "北京"}},
        candidates=[
            {
                "target_node_id": "collect_info",
                "branch": "missing",
                "node": {
                    "id": "collect_info",
                    "type": "form",
                    "config": {
                        "fields": [
                            {"name": "departure_city", "required": True},
                            {"name": "arrival_city", "required": True},
                            {"name": "departure_date", "required": True},
                        ]
                    },
                },
            },
            {
                "target_node_id": "search_flights",
                "branch": "complete",
                "node": {"id": "search_flights", "type": "tool", "config": {"tool_code": "flight_search_api"}},
            },
        ],
        context=context,
    )

    assert decision.target_node_id == "collect_info"
    assert decision.action == "ask_user"
    assert decision.missing_fields == ["arrival_city", "departure_date"]


@pytest.mark.asyncio
async def test_react_decision_routes_to_tool_when_required_slots_complete():
    context = ExecutionContext(
        execution_id="exec-react-complete",
        session_id="session-react-complete",
        workflow_code="flight_booking",
        workflow_version="2.0.0",
    )
    context.add_execution_variables({
        "departure_city": "北京",
        "arrival_city": "上海",
        "departure_date": "2026-06-01",
    })

    service = ReactDecisionService()
    decision = await service.decide_next_node(
        current_node={"id": "extract_slots", "type": "llm", "config": {}},
        result={"output": {"departure_city": "北京", "arrival_city": "上海", "departure_date": "2026-06-01"}},
        candidates=[
            {
                "target_node_id": "collect_info",
                "branch": "missing",
                "node": {
                    "id": "collect_info",
                    "type": "form",
                    "config": {
                        "fields": [
                            {"name": "departure_city", "required": True},
                            {"name": "arrival_city", "required": True},
                            {"name": "departure_date", "required": True},
                        ]
                    },
                },
            },
            {
                "target_node_id": "search_flights",
                "branch": "complete",
                "node": {"id": "search_flights", "type": "tool", "config": {"tool_code": "flight_search_api"}},
            },
        ],
        context=context,
    )

    assert decision.target_node_id == "search_flights"
    assert decision.action == "call_tool"
    assert decision.missing_fields == []


@pytest.mark.asyncio
async def test_react_decision_prefers_model_contract_when_configured(monkeypatch):
    calls = []

    async def fake_completion(**kwargs):
        calls.append(kwargs)
        return '{"targetNodeId":"search_flights","action":"call_tool","missingFields":[],"confidence":0.88,"reasonSummary":"模型判断字段完整，可以查询航班"}'

    monkeypatch.setattr(react_decision_module, "execute_model_completion", fake_completion)
    context = ExecutionContext(
        execution_id="exec-react-model",
        session_id="session-react-model",
        workflow_code="flight_booking",
        workflow_version="2.0.0",
        workflow_config={"decision_policy": {"model_code": "react-router"}},
        provider_configs={"test-provider": {"provider_code": "test-provider", "provider_type": "openai_compatible", "base_url": "https://llm.example.com/v1"}},
        model_records={"react-router": {"model_code": "react-router", "provider_code": "test-provider", "upstream_model_code": "gpt-test"}},
    )
    context.add_execution_variables({"departure_city": "北京", "arrival_city": "上海", "departure_date": "2026-06-01"})

    service = ReactDecisionService()
    decision = await service.decide_next_node(
        current_node={"id": "extract_slots", "type": "llm", "config": {}},
        result={"output": {"departure_city": "北京"}},
        candidates=[
            {
                "target_node_id": "collect_info",
                "branch": "missing",
                "node": {"id": "collect_info", "type": "form", "config": {"fields": []}},
            },
            {
                "target_node_id": "search_flights",
                "branch": "complete",
                "node": {"id": "search_flights", "type": "tool", "config": {"tool_code": "flight_search_api"}},
            },
        ],
        context=context,
    )

    assert calls
    assert decision.target_node_id == "search_flights"
    assert decision.action == "call_tool"
    assert decision.missing_fields == []
    assert decision.confidence == 0.88


@pytest.mark.asyncio
async def test_react_decision_rejects_model_target_that_bypasses_missing_fields(monkeypatch):
    async def fake_completion(**_kwargs):
        return '{"targetNodeId":"search_flights","action":"call_tool","missingFields":[],"confidence":0.99,"reasonSummary":"错误地绕过表单"}'

    monkeypatch.setattr(react_decision_module, "execute_model_completion", fake_completion)
    context = ExecutionContext(
        execution_id="exec-react-model-guard",
        session_id="session-react-model-guard",
        workflow_code="flight_booking",
        workflow_version="2.0.0",
        workflow_config={"decision_policy": {"model_code": "react-router"}},
        provider_configs={"test-provider": {"provider_code": "test-provider", "provider_type": "openai_compatible", "base_url": "https://llm.example.com/v1"}},
        model_records={"react-router": {"model_code": "react-router", "provider_code": "test-provider", "upstream_model_code": "gpt-test"}},
    )
    context.add_execution_variables({"departure_city": "北京"})

    service = ReactDecisionService()
    decision = await service.decide_next_node(
        current_node={"id": "extract_slots", "type": "llm", "config": {}},
        result={"output": {"departure_city": "北京"}},
        candidates=[
            {
                "target_node_id": "collect_info",
                "branch": "missing",
                "node": {
                    "id": "collect_info",
                    "type": "form",
                    "config": {
                        "fields": [
                            {"name": "departure_city", "required": True},
                            {"name": "arrival_city", "required": True},
                            {"name": "departure_date", "required": True},
                        ]
                    },
                },
            },
            {
                "target_node_id": "search_flights",
                "branch": "complete",
                "node": {"id": "search_flights", "type": "tool", "config": {"tool_code": "flight_search_api"}},
            },
        ],
        context=context,
    )

    assert decision.target_node_id == "collect_info"
    assert decision.action == "ask_user"
    assert decision.missing_fields == ["arrival_city", "departure_date"]
