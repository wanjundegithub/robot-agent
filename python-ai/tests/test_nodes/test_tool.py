import pytest
from unittest.mock import AsyncMock, Mock, patch
from src.core.protection import ConfirmationRequiredError, runtime_protection_manager
from src.nodes.tool import ToolNode
from src.core.context import ExecutionContext

@pytest.mark.asyncio
async def test_tool_node_http_call():
    runtime_protection_manager.reset()
    context = ExecutionContext(
        execution_id="exec_test",
        session_id="sess_test",
        workflow_code="flight_booking",
        workflow_version="1.0.0"
    )
    context.add_execution_variable("city", "Beijing")

    node = ToolNode("tool1", {
        "tool": {
            "method": "GET",
            "url": "https://api.example.com/weather?city=${city}",
            "headers": {}
        }
    })

    with patch('src.nodes.tool.httpx.AsyncClient') as mock_client:
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.headers = {"content-type": "application/json"}
        mock_response.json.return_value = {"temperature": 25}
        mock_response.text = '{"temperature": 25}'

        mock_instance = AsyncMock()
        mock_instance.__aenter__.return_value = mock_instance
        mock_instance.__aexit__.return_value = None
        mock_instance.request.return_value = mock_response
        mock_client.return_value = mock_instance

        result = await node.execute(context)
        assert result["status_code"] == 200

@pytest.mark.asyncio
async def test_tool_node_with_params():
    runtime_protection_manager.reset()
    context = ExecutionContext(
        execution_id="exec_test",
        session_id="sess_test",
        workflow_code="flight_booking",
        workflow_version="1.0.0"
    )
    context.add_execution_variable("id", "123")

    node = ToolNode("tool2", {
        "tool": {
            "method": "GET",
            "url": "https://api.example.com/items",
            "params": {"id": {"$ref": "id"}}
        }
    })

    # Test configuration setup
    assert node.method == "GET"
    assert node.url == "https://api.example.com/items"


@pytest.mark.asyncio
async def test_tool_node_retry_and_idempotency():
    runtime_protection_manager.reset()
    context = ExecutionContext(
        execution_id="exec_retry",
        session_id="sess_retry",
        workflow_code="flight_booking",
        workflow_version="2.0.0"
    )
    context.add_execution_variables({
        "departure_city": "北京",
        "arrival_city": "上海",
        "departure_date": "2026-04-09",
        "passengers": 1
    })

    node = ToolNode("search_flights", {
        "config": {
            "tool_code": "flight_search_api",
            "url": "https://tools.example.com/flights/search",
            "method": "POST",
            "retry_policy": "network_timeout",
            "idempotent": True
        }
    })

    with patch('src.core.tool_registry.httpx.AsyncClient') as mock_client:
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.headers = {"content-type": "application/json"}
        mock_response.json.return_value = {
            "flight_options": [{"flight_id": "MU5101", "price": 860}],
            "summary": "找到 1 个航班选项。",
        }

        mock_instance = AsyncMock()
        mock_instance.__aenter__.return_value = mock_instance
        mock_instance.__aexit__.return_value = None
        mock_instance.request.return_value = mock_response
        mock_client.return_value = mock_instance

        first_result = await node.execute(context)
        second_result = await node.execute(context)

    assert first_result["output"]["flight_options"]
    assert first_result["metrics"]["cached"] is False
    assert second_result["metrics"]["cached"] is True


@pytest.mark.asyncio
async def test_tool_node_degrades_when_registry_execute_fails():
    runtime_protection_manager.reset()
    context = ExecutionContext(
        execution_id="exec_degraded",
        session_id="sess_degraded",
        workflow_code="flight_booking",
        workflow_version="2.0.0"
    )

    node = ToolNode("unknown_tool", {
        "config": {
            "tool_code": "unsupported_tool",
            "retry_policy": "validation_error",
            "idempotent": False
        }
    })

    result = await node.execute(context)

    assert result["output"]["tool_status"] == "degraded"
    assert result["metrics"]["degraded"] is True
    assert "message_deltas" not in result


@pytest.mark.asyncio
async def test_tool_node_function_invoke_type_returns_variables():
    runtime_protection_manager.reset()
    context = ExecutionContext(
        execution_id="exec_function_tool",
        session_id="sess_function_tool",
        workflow_code="agent_workflow",
        workflow_version="1.0.0"
    )
    context.add_execution_variables({
        "departure_city": "北京",
        "arrival_city": "上海",
    })

    node = ToolNode("function_tool", {
        "config": {
            "invoke_type": "function",
            "function_name": "extract_slots_summary",
            "payload_mapping": {
                "departure_city": "execution.departure_city",
                "arrival_city": "execution.arrival_city",
            },
            "idempotent": False,
        }
    })

    result = await node.execute(context)

    assert result["output"]["departure_city"] == "北京"
    assert result["output"]["arrival_city"] == "上海"


@pytest.mark.asyncio
async def test_tool_node_blocks_unconfirmed_high_risk_tool():
    runtime_protection_manager.reset()
    context = ExecutionContext(
        execution_id="exec_risk",
        session_id="sess_risk",
        workflow_code="general_query",
        workflow_version="1.0.0"
    )

    node = ToolNode("cancel_order", {
        "config": {
            "tool_code": "cancel_order",
            "retry_policy": "validation_error",
            "idempotent": False
        }
    })

    with pytest.raises(ConfirmationRequiredError):
        await node.execute(context)
