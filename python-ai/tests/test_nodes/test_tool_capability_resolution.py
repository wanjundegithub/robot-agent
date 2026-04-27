import pytest

from src.core.context import ExecutionContext
from src.nodes.tool import ToolNode


@pytest.mark.asyncio
async def test_tool_node_rejects_unresolved_capability_config():
    context = ExecutionContext(
        execution_id="exec_capability",
        session_id="sess_capability",
        workflow_code="capability_workflow",
        workflow_version="1.0.0",
    )

    node = ToolNode("capability_tool", {
        "config": {
            "invoke_type": "capability",
            "group_code": "payment_domain",
            "group_snapshot_version": "v20260425210000",
            "capability_code": "health_check",
        }
    })

    with pytest.raises(RuntimeError, match="must be resolved by Java"):
        await node.execute(context)
