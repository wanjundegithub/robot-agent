import pytest

from src.core.context import ExecutionContext
from src.nodes.subflow import SubflowNode


@pytest.mark.asyncio
async def test_subflow_node_maps_output_to_parent_context():
    context = ExecutionContext(
        execution_id="exec_parent",
        session_id="sess_parent",
        workflow_code="flight_booking",
        workflow_version="2.0.0"
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

    result = await node.execute(context)

    assert result["output"]["seat_available"] is True
    assert context.get_variable("seat_available") is True
    assert context.get_variable("seat_count") == 6
