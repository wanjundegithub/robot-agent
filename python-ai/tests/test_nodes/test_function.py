import pytest

from src.core.context import ExecutionContext
from src.nodes.function import FunctionNode


@pytest.mark.asyncio
async def test_function_assign_operation_updates_context_variables():
    context = ExecutionContext(
        execution_id="exec_function_assign",
        session_id="sess_function_assign",
        workflow_code="travel_assistant",
        workflow_version="v2",
    )
    context.add_execution_variable("user_message", "帮我订票")

    node = FunctionNode("function_assign", {
        "type": "function",
        "config": {
            "operation_type": "assign",
            "assignments": {
                "done": True,
                "echo": "$execution.user_message",
            },
        },
    })

    result = await node.execute(context)

    assert result["output"]["done"] is True
    assert result["output"]["echo"] == "帮我订票"
    assert context.get_variable("done") is True
    assert context.get_variable("echo") == "帮我订票"


@pytest.mark.asyncio
async def test_function_rejects_unsupported_operation():
    context = ExecutionContext(
        execution_id="exec_function_invalid",
        session_id="sess_function_invalid",
        workflow_code="travel_assistant",
        workflow_version="v2",
    )

    node = FunctionNode("function_invalid", {
        "type": "function",
        "config": {
            "operation_type": "http_call",
            "assignments": {},
        },
    })

    with pytest.raises(ValueError, match="Unsupported function operation_type"):
        await node.execute(context)
