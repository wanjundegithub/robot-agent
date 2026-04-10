import pytest
from src.core.state_machine import ExecutionStateMachine, ExecutionStatus, TransitionEvent
from src.core.context import ExecutionContext

def test_state_machine_valid_transition():
    context = ExecutionContext(
        execution_id="exec_test",
        session_id="sess_test",
        workflow_code="flight_booking",
        workflow_version="1.0.0",
        status="pending"
    )
    sm = ExecutionStateMachine()
    sm.set_context(context)

    assert sm.can_transition(TransitionEvent.START)
    sm.transition(TransitionEvent.START)
    assert context.status == "routing"
    assert sm.can_transition(TransitionEvent.ROUTE)
    sm.transition(TransitionEvent.ROUTE)
    assert context.status == "running"

def test_state_machine_invalid_transition():
    context = ExecutionContext(
        execution_id="exec_test",
        session_id="sess_test",
        workflow_code="flight_booking",
        workflow_version="1.0.0",
        status="completed"
    )
    sm = ExecutionStateMachine()
    sm.set_context(context)

    assert not sm.can_transition(TransitionEvent.START)

def test_state_machine_suspend_resume():
    context = ExecutionContext(
        execution_id="exec_test",
        session_id="sess_test",
        workflow_code="flight_booking",
        workflow_version="1.0.0",
        status="running"
    )
    sm = ExecutionStateMachine()
    sm.set_context(context)

    assert sm.can_transition(TransitionEvent.SUSPEND)
    sm.transition(TransitionEvent.SUSPEND)
    assert context.status == "suspended"

    assert sm.can_transition(TransitionEvent.RESUME)
    sm.transition(TransitionEvent.RESUME)
    assert context.status == "running"


def test_state_machine_waiting_user_resume():
    context = ExecutionContext(
        execution_id="exec_wait",
        session_id="sess_wait",
        workflow_code="flight_booking",
        workflow_version="1.0.0",
        status="running"
    )
    sm = ExecutionStateMachine()
    sm.set_context(context)

    assert sm.can_transition(TransitionEvent.WAIT_USER)
    sm.transition(TransitionEvent.WAIT_USER)
    assert context.status == "waiting_user"

    assert sm.can_transition(TransitionEvent.RESUME)
    sm.transition(TransitionEvent.RESUME)
    assert context.status == "running"

def test_state_machine_fail_transition():
    context = ExecutionContext(
        execution_id="exec_test",
        session_id="sess_test",
        workflow_code="flight_booking",
        workflow_version="1.0.0",
        status="running"
    )
    sm = ExecutionStateMachine()
    sm.set_context(context)

    assert sm.can_transition(TransitionEvent.FAIL)
    sm.transition(TransitionEvent.FAIL)
    assert context.status == "failed"
