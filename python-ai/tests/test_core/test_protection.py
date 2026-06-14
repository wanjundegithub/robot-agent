import pytest

from src.core.context import ExecutionContext
from src.core.protection import (
    ConfirmationRequiredError,
    RateLimitExceededError,
    runtime_protection_manager,
    tool_confirmation_gate,
    vector_access_optimizer,
)


def setup_function(_function):
    runtime_protection_manager.reset()
    vector_access_optimizer.reset()


def test_runtime_protection_limits_execution_start_per_session():
    context = ExecutionContext(
        execution_id="exec_limit_1",
        session_id="sess_limit",
        workflow_code="general_query",
        workflow_version="1.0.0",
        user_id="demo-user",
    )

    runtime_protection_manager.check_execution_start(context)
    runtime_protection_manager.check_execution_start(ExecutionContext(
        execution_id="exec_limit_2",
        session_id="sess_limit",
        workflow_code="general_query",
        workflow_version="1.0.0",
        user_id="demo-user",
    ))
    runtime_protection_manager.check_execution_start(ExecutionContext(
        execution_id="exec_limit_3",
        session_id="sess_limit",
        workflow_code="general_query",
        workflow_version="1.0.0",
        user_id="demo-user",
    ))

    with pytest.raises(RateLimitExceededError):
        runtime_protection_manager.check_execution_start(ExecutionContext(
            execution_id="exec_limit_4",
            session_id="sess_limit",
            workflow_code="general_query",
            workflow_version="1.0.0",
            user_id="demo-user",
        ))


def test_tool_confirmation_gate_requires_confirmation_for_high_risk_tool():
    context = ExecutionContext(
        execution_id="exec_risk",
        session_id="sess_risk",
        workflow_code="general_query",
        workflow_version="1.0.0",
        user_id="demo-user",
    )

    with pytest.raises(ConfirmationRequiredError):
        tool_confirmation_gate.ensure_confirmed(context, "cancel_order", {"order_id": "ORD-1001"})


def test_tool_confirmation_gate_allows_confirmed_high_risk_tool():
    context = ExecutionContext(
        execution_id="exec_risk_ok",
        session_id="sess_risk_ok",
        workflow_code="general_query",
        workflow_version="1.0.0",
        user_id="demo-user",
        confirmed_tool_codes=["cancel_order"],
    )

    tool_confirmation_gate.ensure_confirmed(context, "cancel_order", {"order_id": "ORD-1001"})


def test_vector_access_optimizer_builds_stable_plan_and_cache():
    first = vector_access_optimizer.plan("knowledge-demo", "1.0.0", "退票规则")
    second = vector_access_optimizer.plan("knowledge-demo", "1.0.0", "退票规则")

    vector_access_optimizer.put_cached(first.cache_key, [{"content": "cached"}])

    assert first.shard_id == second.shard_id
    assert first.cache_key == second.cache_key
    assert vector_access_optimizer.get_cached(first.cache_key) == [{"content": "cached"}]
