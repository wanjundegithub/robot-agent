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
async def test_function_assign_operation_resolves_nested_object_and_array_templates():
    context = ExecutionContext(
        execution_id="exec_function_nested_assign",
        session_id="sess_function_nested_assign",
        workflow_code="travel_assistant",
        workflow_version="v2",
    )
    context.add_execution_variable("flight_id", "F001")
    context.add_execution_variable("passenger_name", "Alice")
    context.add_execution_variable("passenger_id_no", "P123")
    context.add_execution_variable("tags", ["vip", "window"])

    node = FunctionNode("function_nested_assign", {
        "type": "function",
        "config": {
            "operation_type": "assign",
            "assignments": {
                "bookingRequest": {
                    "flightId": "$execution.flight_id",
                    "passengers": [
                        {
                            "name": "$execution.passenger_name",
                            "idNo": "$execution.passenger_id_no",
                        }
                    ],
                    "tags": "$execution.tags",
                },
            },
        },
    })

    result = await node.execute(context)

    expected = {
        "flightId": "F001",
        "passengers": [{"name": "Alice", "idNo": "P123"}],
        "tags": ["vip", "window"],
    }
    assert result["output"]["bookingRequest"] == expected
    assert context.get_variable("bookingRequest") == expected


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
