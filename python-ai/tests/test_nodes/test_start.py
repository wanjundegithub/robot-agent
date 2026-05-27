import json

import pytest

from src.core.context import ExecutionContext
from src.nodes.start import StartNode


@pytest.mark.asyncio
async def test_start_node_extracts_declared_empty_variables_with_metadata(monkeypatch):
    captured = {}

    async def fake_completion(**kwargs):
        captured.update(kwargs)
        return json.dumps({"variables": {"city": "上海"}}, ensure_ascii=False)

    monkeypatch.setattr("src.nodes.start.execute_model_completion", fake_completion)
    context = ExecutionContext(
        execution_id="exec_start_extract",
        session_id="session_start_extract",
        workflow_code="travel",
        workflow_version="v1",
        workflow_config={"llm_defaults": {"model_code": "slot-model"}},
        provider_configs={"test-provider": {"provider_code": "test-provider"}},
        model_records={"slot-model": {"model_code": "slot-model", "provider_code": "test-provider"}},
    )
    context.add_execution_variable("user_message", "我要去上海")

    node = StartNode("start", {
        "config": {
            "prompt": "请根据用户表达收集出行城市。",
            "initial_variables": {"city": "", "priority": "普通"},
            "input_variables": [
                {"name": "city", "type": "string", "description": "用户要去的城市", "default": ""},
                {"name": "priority", "type": "string", "description": "服务优先级", "default": "普通"},
            ],
        }
    })

    result = await node.execute(context)

    assert result["status"] == "completed"
    assert context.get_variable("city") == "上海"
    assert context.get_variable("priority") == "普通"
    prompt_payload = json.loads(captured["user_prompt"])
    assert prompt_payload["start_node"]["prompt"] == "请根据用户表达收集出行城市。"
    assert prompt_payload["start_node"]["input_variables"][0] == {
        "name": "city",
        "type": "string",
        "description": "用户要去的城市",
        "default": "",
        "current_value": "",
    }
    assert prompt_payload["start_node"]["input_variables"][1]["current_value"] == "普通"


@pytest.mark.asyncio
async def test_start_node_requests_only_missing_declared_variables_without_model():
    context = ExecutionContext(
        execution_id="exec_start_missing",
        session_id="session_start_missing",
        workflow_code="travel",
        workflow_version="v1",
    )
    context.add_execution_variable("user_message", "帮我安排一下")

    node = StartNode("start", {
        "config": {
            "prompt": "缺少出行城市时需要向用户询问。",
            "initial_variables": {"city": "", "priority": "普通"},
            "input_variables": [
                {"name": "city", "type": "string", "description": "用户要去的城市", "default": ""},
                {"name": "priority", "type": "string", "description": "服务优先级", "default": "普通"},
            ],
        }
    })

    result = await node.execute(context)

    assert result["status"] == "suspended"
    assert result["missing_fields"] == ["city"]
    assert "form_definition" not in result
    assert result["slot_request"]["fields"] == [
        {
            "name": "city",
            "type": "string",
            "description": "用户要去的城市",
        }
    ]
    assert result["message_deltas"] == ["请提供用户要去的城市。"]
    assert "city" not in result["message_deltas"][0]
    assert context.get_variable("priority") == "普通"


@pytest.mark.asyncio
async def test_start_node_preserves_existing_non_empty_variable_without_prompting():
    context = ExecutionContext(
        execution_id="exec_start_present",
        session_id="session_start_present",
        workflow_code="travel",
        workflow_version="v1",
    )
    context.add_execution_variable("city", "北京")

    node = StartNode("start", {
        "config": {
            "prompt": "缺少出行城市时需要向用户询问。",
            "initial_variables": {"city": ""},
            "input_variables": [
                {"name": "city", "type": "string", "description": "用户要去的城市", "default": ""},
            ],
        }
    })

    result = await node.execute(context)

    assert result["status"] == "completed"
    assert result["output"] == {"city": "北京"}
    assert context.get_variable("city") == "北京"


@pytest.mark.asyncio
async def test_start_node_extraction_does_not_overwrite_existing_non_empty_values(monkeypatch):
    async def fake_completion(**_kwargs):
        return json.dumps({"variables": {"city": "上海", "date": "2026-06-01"}}, ensure_ascii=False)

    monkeypatch.setattr("src.nodes.start.execute_model_completion", fake_completion)
    context = ExecutionContext(
        execution_id="exec_start_no_overwrite",
        session_id="session_start_no_overwrite",
        workflow_code="travel",
        workflow_version="v1",
        workflow_config={"llm_defaults": {"model_code": "slot-model"}},
        provider_configs={"test-provider": {"provider_code": "test-provider"}},
        model_records={"slot-model": {"model_code": "slot-model", "provider_code": "test-provider"}},
    )
    context.add_execution_variable("user_message", "我要六月一日出发")
    context.add_execution_variable("city", "北京")

    node = StartNode("start", {
        "config": {
            "prompt": "提取城市和日期。",
            "initial_variables": {"city": "", "date": ""},
            "input_variables": [
                {"name": "city", "type": "string", "description": "城市", "default": ""},
                {"name": "date", "type": "date", "description": "日期", "default": ""},
            ],
        }
    })

    result = await node.execute(context)

    assert result["status"] == "completed"
    assert context.get_variable("city") == "北京"
    assert context.get_variable("date") == "2026-06-01"
