import pytest

from src.core.context import ExecutionContext
from src.nodes.end import EndNode


@pytest.mark.asyncio
async def test_end_node_emits_natural_language_message_for_output_variables():
    context = ExecutionContext(
        execution_id="exec_end_output",
        session_id="sess_end_output",
        workflow_code="shopping_assistant",
        workflow_version="v1",
    )
    context.add_execution_variable("product_list", ["卫生纸", "抽纸"])

    node = EndNode("end", {
        "type": "end",
        "config": {
            "prompt": "this prompt is ignored",
            "output_format": {
                "product_list": "$execution.product_list",
            },
        },
    })

    result = await node.execute(context)

    assert result["output"] == {"product_list": ["卫生纸", "抽纸"]}
    assert result["message_deltas"] == ["已为您找到：卫生纸、抽纸。"]


@pytest.mark.asyncio
async def test_end_node_resolves_bare_output_variable_name():
    context = ExecutionContext(
        execution_id="exec_end_bare_output",
        session_id="sess_end_bare_output",
        workflow_code="shopping_assistant",
        workflow_version="v1",
    )
    context.add_execution_variable("product_list", ["卫生纸", "抽纸"])

    node = EndNode("end", {
        "type": "end",
        "config": {
            "output_format": {
                "product_list": "product_list",
            },
        },
    })

    result = await node.execute(context)

    assert result["output"] == {"product_list": ["卫生纸", "抽纸"]}
    assert result["message_deltas"] == ["已为您找到：卫生纸、抽纸。"]


@pytest.mark.asyncio
async def test_end_node_falls_back_to_available_variable_scope():
    context = ExecutionContext(
        execution_id="exec_end_scope_fallback",
        session_id="sess_end_scope_fallback",
        workflow_code="shopping_assistant",
        workflow_version="v1",
    )
    context.add_session_variable("product_list", ["卫生纸", "抽纸"])

    node = EndNode("end", {
        "type": "end",
        "config": {
            "output_format": {
                "product_list": "$execution.product_list",
            },
        },
    })

    result = await node.execute(context)

    assert result["output"] == {"product_list": ["卫生纸", "抽纸"]}
    assert result["message_deltas"] == ["已为您找到：卫生纸、抽纸。"]
