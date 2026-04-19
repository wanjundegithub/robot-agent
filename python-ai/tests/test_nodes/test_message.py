import pytest

from src.core.context import ExecutionContext
from src.nodes.message import MessageNode


@pytest.mark.asyncio
async def test_message_node_outputs_fixed_phrase():
    context = ExecutionContext(
        execution_id="exec_message",
        session_id="sess_message",
        workflow_code="agent_workflow",
        workflow_version="1.0.0",
    )

    node = MessageNode("message_1", {
        "config": {
            "message_text": "好的，我正在为你处理，请稍候。",
        }
    })

    result = await node.execute(context)

    assert result["output"]["answer"] == "好的，我正在为你处理，请稍候。"
    assert result["message_deltas"] == ["好的，我正在为你处理，请稍候。"]
    assert context.get_variable("answer") == "好的，我正在为你处理，请稍候。"
