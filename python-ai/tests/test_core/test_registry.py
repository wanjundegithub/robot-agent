import pytest

from src.core.protection import runtime_protection_manager
from src.core.registry import ExecutionRegistry


@pytest.mark.asyncio
async def test_registry_returns_existing_runtime_for_duplicate_message_id():
    runtime_protection_manager.reset()
    registry = ExecutionRegistry()

    payload = {
        "session_id": "sess_dup",
        "execution_id": "exec_1",
        "workflow_code": "general_query",
        "workflow_version": "1.0.0",
        "message_id": "msg_dup",
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

    await registry.create_execution({
        "session_id": "sess_limit",
        "execution_id": "exec_1",
        "workflow_code": "general_query",
        "workflow_version": "1.0.0",
        "message_id": "msg_1",
        "input_variables": {"user_message": "退票规则是什么"},
    })
    await registry.create_execution({
        "session_id": "sess_limit",
        "execution_id": "exec_2",
        "workflow_code": "hotel_booking",
        "workflow_version": "1.0.0",
        "message_id": "msg_2",
        "input_variables": {"user_message": "订酒店"},
    })

    with pytest.raises(ValueError, match="Session concurrency limit reached"):
        await registry.create_execution({
            "session_id": "sess_limit",
            "execution_id": "exec_3",
            "workflow_code": "flight_booking",
            "workflow_version": "1.0.0",
            "message_id": "msg_3",
            "input_variables": {"user_message": "订机票"},
        })
