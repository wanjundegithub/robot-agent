import pytest

from src.core.protection import runtime_protection_manager
from src.core.registry import ExecutionRegistry
from tests.fixtures.workflows import simple_workflow


@pytest.mark.asyncio
async def test_registry_returns_existing_runtime_for_duplicate_message_id():
    runtime_protection_manager.reset()
    registry = ExecutionRegistry()
    workflow_definition = simple_workflow("general_query", "1.0.0")

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
    general_query = simple_workflow("general_query", "1.0.0")
    hotel_booking = simple_workflow("hotel_booking", "1.0.0")
    flight_booking = simple_workflow("flight_booking", "1.0.0")

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


@pytest.mark.asyncio
async def test_registry_rejects_missing_top_level_workflow_definition_by_default():
    runtime_protection_manager.reset()
    registry = ExecutionRegistry()

    with pytest.raises(ValueError, match="Workflow definition missing"):
        await registry.create_execution({
            "session_id": "sess_builtin_missing",
            "execution_id": "exec_builtin_missing",
            "workflow_code": "flight_booking",
            "workflow_version": "1.0.0",
            "message_id": "msg_builtin_missing",
            "input_variables": {"user_message": "订机票"},
        })


@pytest.mark.asyncio
async def test_registry_normalizes_node_references_to_variable_registry_scope():
    runtime_protection_manager.reset()
    registry = ExecutionRegistry()
    workflow = {
        "schema_version": "workflow-designer/v2",
        "main_graph_id": "main",
        "variables": {
            "global": [],
            "temporary": [
                {"name": "product_name", "scope": "temp", "type": "String"},
                {"name": "product_list", "scope": "temp", "type": "Array"},
            ],
        },
        "graphs": {
            "main": {
                "graph_id": "main",
                "graph_type": "main",
                "entry_node_id": "api",
                "nodes": {
                    "api": {
                        "id": "api",
                        "type": "api",
                        "config": {
                            "payload_mapping": {"product_name": "$session.product_name"},
                            "output_mapping": {"result": "$session.product_list"},
                        },
                    },
                    "end": {
                        "id": "end",
                        "type": "end",
                        "config": {"output_format": {"product_list": "$session.product_list"}},
                    },
                },
                "edges": [{"id": "e1", "source": "api", "target": "end"}],
            },
        },
    }

    runtime = await registry.create_execution({
        "session_id": "sess_scope_normalize",
        "execution_id": "exec_scope_normalize",
        "workflow_code": "shopping",
        "workflow_version": "v1",
        "workflow_definition": workflow,
    })

    nodes = runtime.workflow["graphs"]["main"]["nodes"]
    assert nodes["api"]["config"]["payload_mapping"] == {"product_name": "$execution.product_name"}
    assert nodes["api"]["config"]["output_mapping"] == {"result": "$execution.product_list"}
    assert nodes["end"]["config"]["output_format"] == {"product_list": "$execution.product_list"}


@pytest.mark.asyncio
async def test_registry_returns_existing_runtime_for_duplicate_execution_id():
    runtime_protection_manager.reset()
    registry = ExecutionRegistry()
    workflow = simple_workflow("flight_booking", "1.0.0")

    payload = {
        "session_id": "sess_same_exec",
        "execution_id": "exec_same",
        "workflow_code": "flight_booking",
        "workflow_version": "1.0.0",
        "workflow_definition": workflow,
        "workflow_catalog": {"flight_booking@1.0.0": workflow},
        "input_variables": {"user_message": "订机票"},
    }

    first_runtime = await registry.create_execution(payload)
    second_runtime = await registry.create_execution(payload)

    assert second_runtime is first_runtime
