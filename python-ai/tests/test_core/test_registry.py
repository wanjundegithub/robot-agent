import pytest

from src.core.protection import runtime_protection_manager
from src.core.registry import ExecutionRegistry
from src.core.workflow_registry import get_workflow


@pytest.mark.asyncio
async def test_registry_returns_existing_runtime_for_duplicate_message_id():
    runtime_protection_manager.reset()
    registry = ExecutionRegistry()
    workflow_definition = get_workflow("general_query", "1.0.0")

    payload = {
        "session_id": "sess_dup",
        "execution_id": "exec_1",
        "workflow_code": "general_query",
        "workflow_version": "1.0.0",
        "message_id": "msg_dup",
        "workflow_definition": workflow_definition,
        "workflow_catalog": {"general_query@1.0.0": workflow_definition},
        "input_variables": {"user_message": "退票规则是什么"},
    }
    duplicate_payload = {
        **payload,
        "execution_id": "exec_2",
    }

    first_runtime = await registry.create_execution(payload)
    second_runtime = await registry.create_execution(duplicate_payload)

    assert first_runtime is second_runtime
    assert second_runtime.context.execution_id == "exec_1"


@pytest.mark.asyncio
async def test_registry_enforces_session_concurrency_limit():
    runtime_protection_manager.reset()
    registry = ExecutionRegistry()
    general_query = get_workflow("general_query", "1.0.0")
    hotel_booking = get_workflow("hotel_booking", "1.0.0")
    flight_booking = get_workflow("flight_booking", "1.0.0")

    await registry.create_execution({
        "session_id": "sess_limit",
        "execution_id": "exec_1",
        "workflow_code": "general_query",
        "workflow_version": "1.0.0",
        "message_id": "msg_1",
        "workflow_definition": general_query,
        "workflow_catalog": {"general_query@1.0.0": general_query},
        "input_variables": {"user_message": "退票规则是什么"},
    })
    await registry.create_execution({
        "session_id": "sess_limit",
        "execution_id": "exec_2",
        "workflow_code": "hotel_booking",
        "workflow_version": "1.0.0",
        "message_id": "msg_2",
        "workflow_definition": hotel_booking,
        "workflow_catalog": {"hotel_booking@1.0.0": hotel_booking},
        "input_variables": {"user_message": "订酒店"},
    })

    with pytest.raises(ValueError, match="Session concurrency limit reached"):
        await registry.create_execution({
            "session_id": "sess_limit",
            "execution_id": "exec_3",
            "workflow_code": "flight_booking",
            "workflow_version": "1.0.0",
            "message_id": "msg_3",
            "workflow_definition": flight_booking,
            "workflow_catalog": {"flight_booking@1.0.0": flight_booking},
            "input_variables": {"user_message": "订机票"},
        })
