import pytest

from src.core.context import ExecutionContext
from src.nodes.function import FunctionNode


@pytest.mark.asyncio
async def test_function_fragment_updates_session_and_execution_variables():
    context = ExecutionContext(
        execution_id="exec_function_fragment",
        session_id="sess_function_fragment",
        workflow_code="travel_assistant",
        workflow_version="v2",
    )
    context.add_session_variable("user_name", "张三")
    context.add_execution_variable("order_id", "A001")

    node = FunctionNode("function_fragment", {
        "type": "function",
        "config": {
            "language": "python",
            "function_name": "处理订单变量",
            "code": (
                "print('开始处理')\n"
                "ctx['global']['user_name'] = ctx['global']['user_name'].strip()\n"
                "ctx['local']['result'] = ctx['global']['user_name'] + ':' + ctx['local']['order_id']"
            ),
            "timeout_ms": 3000,
        },
    })

    result = await node.execute(context)

    assert result["status"] == "completed"
    assert result["operation_type"] == "fragment"
    assert result["output"] == {"order_id": "A001", "result": "张三:A001"}
    assert result["stdout"] == "开始处理\n"
    assert context.session_variables == {"user_name": "张三"}
    assert context.execution_variables == {"order_id": "A001", "result": "张三:A001"}


@pytest.mark.asyncio
async def test_function_fragment_allows_return_without_value():
    context = ExecutionContext(
        execution_id="exec_function_return",
        session_id="sess_function_return",
        workflow_code="travel_assistant",
        workflow_version="v2",
    )
    context.add_execution_variable("order_id", "")

    node = FunctionNode("function_return", {
        "type": "function",
        "config": {
            "code": (
                "if not ctx['local'].get('order_id'):\n"
                "    print('缺少订单号')\n"
                "    return\n"
                "ctx['local']['checked'] = True"
            ),
        },
    })

    result = await node.execute(context)

    assert result["output"] == {"order_id": ""}
    assert result["stdout"] == "缺少订单号\n"
    assert "checked" not in context.execution_variables


@pytest.mark.asyncio
async def test_function_fragment_rejects_dangerous_code():
    context = ExecutionContext(
        execution_id="exec_function_invalid",
        session_id="sess_function_invalid",
        workflow_code="travel_assistant",
        workflow_version="v2",
    )

    node = FunctionNode("function_invalid", {
        "type": "function",
        "config": {
            "code": "import os",
        },
    })

    with pytest.raises(ValueError, match="禁用语法: import"):
        await node.execute(context)


@pytest.mark.asyncio
async def test_function_fragment_preserves_internal_execution_variables():
    emitted = []
    context = ExecutionContext(
        execution_id="exec_function_internal",
        session_id="sess_function_internal",
        workflow_code="travel_assistant",
        workflow_version="v2",
    )
    context.add_execution_variable("_emit_message_delta", lambda content, is_complete=False: emitted.append((content, is_complete)))
    context.add_execution_variable("order_id", "A001")

    node = FunctionNode("function_internal", {
        "type": "function",
        "config": {
            "code": "ctx['local']['result'] = ctx['local']['order_id'] + ':ok'",
        },
    })

    result = await node.execute(context)

    assert result["output"] == {"order_id": "A001", "result": "A001:ok"}
    assert callable(context.execution_variables["_emit_message_delta"])
    assert "_emit_message_delta" not in result["output"]
