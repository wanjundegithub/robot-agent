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
