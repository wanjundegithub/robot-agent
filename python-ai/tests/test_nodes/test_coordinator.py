import pytest

from src.core.context import ExecutionContext
from src.nodes.coordinator import CoordinatorNode


@pytest.mark.asyncio
async def test_coordinator_auto_selects_only_candidate():
    context = ExecutionContext(
        execution_id="exec_coordinator_single",
        session_id="sess_coordinator_single",
        workflow_code="travel_assistant",
        workflow_version="v2",
    )
    context.available_targets = ["message_1"]

    node = CoordinatorNode("coordinator_1", {"type": "coordinator", "config": {}})
    result = await node.execute(context)

    assert result["next_node"] == "message_1"
    assert result["output"]["targetNodeId"] == "message_1"


@pytest.mark.asyncio
async def test_coordinator_requires_valid_target_when_multiple_candidates():
    context = ExecutionContext(
        execution_id="exec_coordinator_multi",
        session_id="sess_coordinator_multi",
        workflow_code="travel_assistant",
        workflow_version="v2",
    )
    context.available_targets = ["tool_a", "tool_b"]
    context.add_execution_variable("targetNodeId", "tool_b")

    node = CoordinatorNode("coordinator_1", {"type": "coordinator", "config": {}})
    result = await node.execute(context)

    assert result["next_node"] == "tool_b"
    assert result["output"]["targetNodeId"] == "tool_b"
    assert "targetNodeId" not in context.execution_variables


@pytest.mark.asyncio
async def test_coordinator_raises_for_invalid_target():
    context = ExecutionContext(
        execution_id="exec_coordinator_invalid",
        session_id="sess_coordinator_invalid",
        workflow_code="travel_assistant",
        workflow_version="v2",
    )
    context.available_targets = ["tool_a", "tool_b"]
    context.add_execution_variable("targetNodeId", "tool_x")

    node = CoordinatorNode("coordinator_1", {"type": "coordinator", "config": {}})

    with pytest.raises(ValueError, match="Invalid targetNodeId"):
        await node.execute(context)


@pytest.mark.asyncio
async def test_coordinator_requires_explicit_target_for_multiple_candidates():
    context = ExecutionContext(
        execution_id="exec_coordinator_missing",
        session_id="sess_coordinator_missing",
        workflow_code="travel_assistant",
        workflow_version="v2",
    )
    context.available_targets = ["tool_a", "tool_b"]

    node = CoordinatorNode("coordinator_1", {"type": "coordinator", "config": {}})

    with pytest.raises(ValueError, match="must return targetNodeId"):
        await node.execute(context)


@pytest.mark.asyncio
async def test_coordinator_uses_llm_json_contract_for_target_and_welcome_message(monkeypatch):
    calls = []

    async def fake_completion(**kwargs):
        calls.append(kwargs)
        return '{"targetNodeId":"hotel_agent","message":"您好，欢迎使用酒店预订助手。","reason":"用户想订酒店"}'

    monkeypatch.setattr("src.nodes.coordinator.execute_model_completion", fake_completion, raising=False)
    context = ExecutionContext(
        execution_id="exec_coordinator_llm",
        session_id="sess_coordinator_llm",
        workflow_code="travel_assistant",
        workflow_version="v2",
        workflow_config={"llm_defaults": {"model_code": "general-chat-v1"}},
    )
    context.available_targets = ["flight_agent", "hotel_agent"]
    context.add_execution_variable("user_message", "我要订酒店")

    node = CoordinatorNode("coordinator_1", {
        "type": "coordinator",
        "config": {
            "system_prompt": "你是协调节点，请根据用户意图选择子代理。",
            "prompt": "对话开始先说欢迎语，然后选择最合适的子代理。",
        },
    })
    result = await node.execute(context)

    assert result["next_node"] == "hotel_agent"
    assert result["output"]["targetNodeId"] == "hotel_agent"
    assert result["output"]["reason"] == "用户想订酒店"
    assert result["message_deltas"] == ["您好，欢迎使用酒店预订助手。"]
    assert calls[0]["model_code"] == "general-chat-v1"
    assert calls[0]["system_prompt"] == "你是协调节点，请根据用户意图选择子代理。"
    assert "对话开始先说欢迎语" in calls[0]["user_prompt"]
    assert "flight_agent" in calls[0]["user_prompt"]
    assert "hotel_agent" in calls[0]["user_prompt"]


@pytest.mark.asyncio
async def test_coordinator_prefers_candidate_text_match_before_llm(monkeypatch):
    calls = []

    async def fake_completion(**kwargs):
        calls.append(kwargs)
        return '{"targetNodeId":"product_agent","reason":"wrong fallback"}'

    monkeypatch.setattr("src.nodes.coordinator.execute_model_completion", fake_completion, raising=False)
    context = ExecutionContext(
        execution_id="exec_coordinator_candidate_text",
        session_id="sess_coordinator_candidate_text",
        workflow_code="product_or_goods_lookup",
        workflow_version="v2",
        workflow_config={"llm_defaults": {"model_code": "general-chat-v1"}},
    )
    context.available_targets = ["product_agent", "goods_agent"]
    context.workflow_node_context = [
        {
            "id": "product_agent",
            "type": "sub_agent",
            "name": "查询商品",
            "description": "专门为查询商品的服务",
            "prompt": "专门为查询商品的服务",
        },
        {
            "id": "goods_agent",
            "type": "sub_agent",
            "name": "查询货物",
            "description": "查询货物子流程",
            "prompt": "查询货物子流程",
        },
    ]
    context.add_execution_variable("user_message", "我要查询货物")

    node = CoordinatorNode("coordinator_1", {
        "type": "coordinator",
        "config": {"prompt": "根据用户意图选择要进入的子代理流程。"},
    })
    result = await node.execute(context)

    assert result["next_node"] == "goods_agent"
    assert result["output"]["targetNodeId"] == "goods_agent"
    assert result["output"]["reason"] == "candidate_text_match"
    assert calls == []


@pytest.mark.asyncio
async def test_coordinator_ignores_configured_welcome_message_without_llm():
    context = ExecutionContext(
        execution_id="exec_coordinator_welcome",
        session_id="sess_coordinator_welcome",
        workflow_code="travel_assistant",
        workflow_version="v2",
    )
    context.available_targets = ["message_1"]

    node = CoordinatorNode("coordinator_1", {
        "type": "coordinator",
        "config": {"welcome_message": "您好，我是机器人助手。"},
    })
    result = await node.execute(context)

    assert result["next_node"] == "message_1"
    assert "message_deltas" not in result


@pytest.mark.asyncio
async def test_coordinator_uses_llm_prompt_to_emit_welcome_message_for_single_candidate(monkeypatch):
    calls = []

    async def fake_completion(**kwargs):
        calls.append(kwargs)
        return '{"message":"您好，我是根据协调节点提示词生成的欢迎语。","reason":"先欢迎用户"}'

    monkeypatch.setattr("src.nodes.coordinator.execute_model_completion", fake_completion, raising=False)
    context = ExecutionContext(
        execution_id="exec_coordinator_single_llm_welcome",
        session_id="sess_coordinator_single_llm_welcome",
        workflow_code="travel_assistant",
        workflow_version="v2",
        workflow_config={"llm_defaults": {"model_code": "general-chat-v1"}},
    )
    context.available_targets = ["message_1"]
    context.add_execution_variable("user_message", "你好")

    node = CoordinatorNode("coordinator_1", {
        "type": "coordinator",
        "config": {"prompt": "对话开始时，请根据当前用户输入生成欢迎语。"},
    })
    result = await node.execute(context)

    assert result["next_node"] == "message_1"
    assert result["message_deltas"] == ["您好，我是根据协调节点提示词生成的欢迎语。"]
    assert result["output"]["reason"] == "先欢迎用户"
    assert "对话开始时" in calls[0]["user_prompt"]


@pytest.mark.asyncio
async def test_coordinator_uses_workflow_configured_control_system_prompt(monkeypatch):
    calls = []

    async def fake_completion(**kwargs):
        calls.append(kwargs)
        return '{"targetNodeId":"flight_agent","reason":"用户想订机票"}'

    monkeypatch.setattr("src.nodes.coordinator.execute_model_completion", fake_completion, raising=False)
    context = ExecutionContext(
        execution_id="exec_coordinator_config_prompt",
        session_id="sess_coordinator_config_prompt",
        workflow_code="travel_assistant",
        workflow_version="v2",
        workflow_config={
            "llm_defaults": {"model_code": "general-chat-v1"},
            "system_prompts": {
                "workflow_control": "配置中心系统提示词：禁止构造流程外槽位。",
            },
        },
    )
    context.available_targets = ["flight_agent"]
    context.add_execution_variable("user_message", "我要预定机票")

    node = CoordinatorNode("coordinator_1", {
        "type": "coordinator",
        "config": {"prompt": "根据用户输入选择子代理。"},
    })
    result = await node.execute(context)

    assert result["next_node"] == "flight_agent"
    assert calls[0]["system_prompt"] == "配置中心系统提示词：禁止构造流程外槽位。"
