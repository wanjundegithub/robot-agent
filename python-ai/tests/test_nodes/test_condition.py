import pytest
from src.nodes.condition import ConditionNode
from src.core.context import ExecutionContext

@pytest.mark.asyncio
async def test_condition_node_equals():
    context = ExecutionContext(
        execution_id="exec_test",
        session_id="sess_test",
        workflow_code="flight_booking",
        workflow_version="1.0.0"
    )
    context.add_execution_variable("score", 100)

    node = ConditionNode("cond1", {
        "condition": {"type": "equals", "field": "score", "value": 100},
        "branches": {"true": "node_a", "false": "node_b"}
    })

    result = await node.execute(context)
    assert result["condition_met"] is True
    assert result["next_node"] == "node_a"

@pytest.mark.asyncio
async def test_condition_node_contains():
    context = ExecutionContext(
        execution_id="exec_test",
        session_id="sess_test",
        workflow_code="flight_booking",
        workflow_version="1.0.0"
    )
    context.add_execution_variable("message", "你好世界")

    node = ConditionNode("cond2", {
        "condition": {"type": "contains", "field": "message", "value": "世界"},
        "branches": {"true": "node_a", "false": "node_b"}
    })

    result = await node.execute(context)
    assert result["condition_met"] is True

@pytest.mark.asyncio
async def test_condition_node_greater_than():
    context = ExecutionContext(
        execution_id="exec_test",
        session_id="sess_test",
        workflow_code="flight_booking",
        workflow_version="1.0.0"
    )
    context.add_execution_variable("age", 25)

    node = ConditionNode("cond3", {
        "condition": {"type": "greater_than", "field": "age", "value": 18},
        "branches": {"true": "node_adult", "false": "node_minor"}
    })

    result = await node.execute(context)
    assert result["condition_met"] is True
