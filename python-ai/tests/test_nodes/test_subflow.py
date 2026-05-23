import pytest
from unittest.mock import AsyncMock, Mock, patch

from src.core.context import ExecutionContext
from src.nodes.subflow import SubflowNode
from tests.fixtures.workflows import seat_check_workflow


@pytest.mark.asyncio
async def test_subflow_node_maps_output_to_parent_context():
    seat_check = seat_check_workflow()
    context = ExecutionContext(
        execution_id="exec_parent",
        session_id="sess_parent",
        workflow_code="flight_booking",
        workflow_version="2.0.0",
        workflow_catalog={"seat_check@1.0.0": seat_check},
    )
    context.add_execution_variables({
        "flight_options": [{"flight_id": "MU5101"}],
        "departure_date": "2026-04-09"
    })

    node = SubflowNode("check_seat_availability", {
        "config": {
            "subflow_code": "seat_check",
            "subflow_version": "1.0.0",
            "input_mapping": {
                "flight_id": "$execution.flight_options.0.flight_id",
                "departure_date": "$execution.departure_date"
            },
            "output_mapping": {
                "$execution.seat_available": "$subflow.output.seat_available",
                "$execution.seat_count": "$subflow.output.seat_count"
            }
        }
    })

    with patch('src.core.tool_registry.httpx.AsyncClient') as mock_client:
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.headers = {"content-type": "application/json"}
        mock_response.json.return_value = {"available": True, "count": 6}

        mock_instance = AsyncMock()
        mock_instance.__aenter__.return_value = mock_instance
        mock_instance.__aexit__.return_value = None
        mock_instance.request.return_value = mock_response
        mock_client.return_value = mock_instance

        result = await node.execute(context)

    assert result["output"]["seat_available"] is True
    assert context.get_variable("seat_available") is True
    assert context.get_variable("seat_count") == 6


@pytest.mark.asyncio
async def test_subflow_node_fails_when_workflow_catalog_is_missing_subflow():
    context = ExecutionContext(
        execution_id="exec_parent_builtin",
        session_id="sess_parent_builtin",
        workflow_code="flight_booking",
        workflow_version="2.0.0",
        workflow_catalog={},
    )
    context.add_execution_variables({
        "flight_options": [{"flight_id": "CA9999"}],
        "departure_date": "2026-04-10"
    })

    node = SubflowNode("check_seat_availability", {
        "config": {
            "subflow_code": "seat_check",
            "subflow_version": "1.0.0",
            "input_mapping": {
                "flight_id": "$execution.flight_options.0.flight_id",
                "departure_date": "$execution.departure_date"
            },
            "output_mapping": {
                "$execution.seat_available": "$subflow.output.seat_available",
                "$execution.seat_count": "$subflow.output.seat_count"
            }
        }
    })

    with pytest.raises(ValueError, match="Subflow not found: seat_check@1.0.0"):
        await node.execute(context)
