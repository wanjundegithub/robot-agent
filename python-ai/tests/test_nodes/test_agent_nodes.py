import pytest

from src.core.context import ExecutionContext
from src.core.scheduler import WorkflowScheduler
from src.nodes.llm import LLMNode


def async_result(value: str):
    async def _runner(**_kwargs):
        return value
    return _runner


def test_scheduler_builds_coordinate_and_sub_agent_as_llm_nodes():
    scheduler = WorkflowScheduler()

    coordinate = scheduler._build_node({"id": "coordinate_1", "type": "coordinate", "config": {"prompt": "协调"}})
    sub_agent = scheduler._build_node({"id": "sub_agent_1", "type": "sub_agent", "config": {"prompt": "执行"}})

    assert isinstance(coordinate, LLMNode)
    assert isinstance(sub_agent, LLMNode)


@pytest.mark.asyncio
async def test_coordinate_node_executes_prompt_only(monkeypatch):
    context = ExecutionContext(
        execution_id="exec_coordinate",
        session_id="sess_coordinate",
        workflow_code="agent_workflow",
        workflow_version="1.0.0",
        workflow_config={"llm_defaults": {"model_profile_ref": "general-chat-v1"}},
        provider_configs={
            "openai-compatible-prod": {
                "provider_code": "openai-compatible-prod",
                "provider_type": "openai_compatible",
                "base_url": "https://llm.example.com/v1",
            }
        },
        model_profiles={
            "general-chat-v1": {
                "profile_code": "general-chat-v1",
                "provider_code": "openai-compatible-prod",
                "model_code": "qwen-plus",
            }
        },
    )
    context.add_execution_variable("user_message", "请协调完成用户请求")

    node = LLMNode("coordinate_1", {
        "type": "coordinate",
        "config": {
            "prompt": "coordinate_prompt",
            "user_prompt": "请协调 sub-agent 处理：{user_message}",
        },
    })
    monkeypatch.setattr("src.nodes.llm.execute_profile_completion", async_result("协调完成，请继续处理。"))

    result = await node.execute(context)

    assert result["output"]["text"] == "协调完成，请继续处理。"
