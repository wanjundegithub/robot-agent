from src.core.context import ExecutionContext
from src.core.context_assembler import ContextAssembler
from src.core.node_readiness import NodeReadinessChecker
from src.core.path_resolver import PathResolver
from src.core.planner import Planner
from src.core.workflow_registry import get_workflow


def test_planner_selects_current_node_with_reasoning():
    workflow = get_workflow("flight_booking", "1.0.0")
    context = ExecutionContext(
        execution_id="exec_plan",
        session_id="sess_plan",
        workflow_code="flight_booking",
        workflow_version="1.0.0",
    )

    planning_context = ContextAssembler().build(context, workflow, "collect_info")
    plan = Planner().plan(planning_context)
    decision = PathResolver().resolve(plan, workflow, context)

    assert plan.selected_node == "collect_info"
    assert plan.candidate_nodes == ["collect_info"]
    assert decision.selected_node == "collect_info"


def test_node_readiness_skips_form_when_required_fields_present():
    workflow = get_workflow("flight_booking", "1.0.0")
    context = ExecutionContext(
        execution_id="exec_ready",
        session_id="sess_ready",
        workflow_code="flight_booking",
        workflow_version="1.0.0",
    )
    context.add_execution_variables({
        "departure_city": "上海",
        "arrival_city": "北京",
        "departure_date": "2026-04-21",
    })

    readiness = NodeReadinessChecker().check(
        workflow["nodes"]["collect_info"],
        context,
        workflow,
    )

    assert readiness.should_skip is True
    assert readiness.next_node == "end"
