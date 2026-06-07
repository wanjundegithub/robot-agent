import pytest
from unittest.mock import AsyncMock, Mock, patch
from src.core.protection import ConfirmationRequiredError, runtime_protection_manager
from src.core.idempotency import get_idempotency_store
from src.core.tool_registry import tool_registry
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
async def test_tool_node_resolves_url_template_from_payload_mapping():
    runtime_protection_manager.reset()
    context = ExecutionContext(
        execution_id="exec_url_template",
        session_id="sess_url_template",
        workflow_code="agent_workflow",
        workflow_version="1.0.0"
    )
    context.add_session_variable("order_id", "A 123")
    context.add_execution_variable("status", "paid")

    node = ToolNode("api_tool", {
        "config": {
            "tool_code": "api-1",
            "invoke_type": "api",
            "method": "POST",
            "url": "https://api.example.com/orders/{orderId}/status",
            "headers": {},
            "payload_mapping": {
                "orderId": "$session.order_id",
                "status": "$execution.status",
            },
            "idempotent": False,
        }
    })

    with patch('src.core.tool_registry.httpx.AsyncClient') as mock_client:
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.headers = {"content-type": "application/json"}
        mock_response.json.return_value = {"ok": True}
        mock_response.text = '{"ok": true}'

        mock_instance = AsyncMock()
        mock_instance.__aenter__.return_value = mock_instance
        mock_instance.__aexit__.return_value = None
        mock_instance.request.return_value = mock_response
        mock_client.return_value = mock_instance

        result = await node.execute(context)

    assert result["output"] == {"ok": True}
    mock_instance.request.assert_awaited_once()
    request_kwargs = mock_instance.request.await_args.kwargs
    assert request_kwargs["url"] == "https://api.example.com/orders/A%20123/status"
    assert request_kwargs["json"] == {"status": "paid"}


@pytest.mark.asyncio
async def test_tool_node_parses_json_body_when_content_type_is_plain_text():
    runtime_protection_manager.reset()
    context = ExecutionContext(
        execution_id="exec_plain_text_json",
        session_id="sess_plain_text_json",
        workflow_code="agent_workflow",
        workflow_version="1.0.0"
    )
    context.add_execution_variable("product_name", "小苏打")

    node = ToolNode("api_tool", {
        "config": {
            "tool_code": "api-plain-json",
            "invoke_type": "api",
            "method": "POST",
            "url": "https://api.example.com/search",
            "headers": {},
            "payload_mapping": {
                "product_name": "$execution.product_name",
            },
            "output_mapping": {
                "result": "$execution.product_list",
            },
            "idempotent": False,
        }
    })

    with patch('src.core.tool_registry.httpx.AsyncClient') as mock_client:
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.headers = {"content-type": "text/plain; charset=utf-8"}
        mock_response.text = '{"result":[["小苏打牙膏","100"]]}'

        mock_instance = AsyncMock()
        mock_instance.__aenter__.return_value = mock_instance
        mock_instance.__aexit__.return_value = None
        mock_instance.request.return_value = mock_response
        mock_client.return_value = mock_instance

        result = await node.execute(context)

    assert result["output"] == {"result": [["小苏打牙膏", "100"]]}
    assert context.execution_variables["product_list"] == [["小苏打牙膏", "100"]]


@pytest.mark.asyncio
async def test_tool_node_normalizes_cached_raw_response_json_for_output_mapping():
    runtime_protection_manager.reset()
    context = ExecutionContext(
        execution_id="exec_cached_plain_text_json",
        session_id="sess_cached_plain_text_json",
        workflow_code="agent_workflow",
        workflow_version="1.0.0"
    )
    context.add_execution_variable("product_name", "小苏打缓存")

    params = {"product_name": "小苏打缓存"}
    cache_key = tool_registry._tool_key("api-cached-json", params)
    get_idempotency_store().set_json(
        cache_key,
        {"raw_response": '{"result":[["小苏打粉清洁去污","100"]]}'},
        3600,
    )

    node = ToolNode("api_tool", {
        "config": {
            "tool_code": "api-cached-json",
            "invoke_type": "api",
            "method": "POST",
            "url": "https://api.example.com/search",
            "headers": {},
            "payload_mapping": {
                "product_name": "$execution.product_name",
            },
            "output_mapping": {
                "result": "$execution.product_list",
            },
        }
    })

    result = await node.execute(context)

    assert result["output"] == {
        "result": [["小苏打粉清洁去污", "100"]],
        "cached": True,
    }
    assert context.execution_variables["product_list"] == [["小苏打粉清洁去污", "100"]]


@pytest.mark.asyncio
async def test_tool_node_applies_output_mapping_to_variables():
    runtime_protection_manager.reset()
    context = ExecutionContext(
        execution_id="exec_output_mapping",
        session_id="sess_output_mapping",
        workflow_code="agent_workflow",
        workflow_version="1.0.0"
    )

    node = ToolNode("mapped_tool", {
        "config": {
            "invoke_type": "function",
            "function_name": "extract_slots_summary",
            "payload_mapping": {
                "price": 1280,
                "currency": "CNY",
            },
            "output_mapping": {
                "price": "$execution.best_price",
                "currency": "$session.currency",
            },
            "idempotent": False,
        }
    })

    result = await node.execute(context)

    assert result["output"] == {"price": 1280, "currency": "CNY"}
    assert context.execution_variables["best_price"] == 1280
    assert context.session_variables["currency"] == "CNY"
    assert "price" not in context.execution_variables


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
