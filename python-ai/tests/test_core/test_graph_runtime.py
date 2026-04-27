import json

import pytest

from src.core.costing import BudgetAlert
from src.core.protection import runtime_protection_manager
from src.core.registry import ExecutionRegistry
from src.core.scheduler import WorkflowScheduler


async def _collect_events(runtime):
    entries = []
    async for item in runtime.stream():
        event_type = ""
        payload = {}
        for line in item.splitlines():
            if line.startswith("event: "):
                event_type = line[len("event: "):]
            if line.startswith("data: "):
                payload = json.loads(line[len("data: "):])
        entries.append((event_type, payload))
    return entries


def async_result(value: str):
    async def _runner(**_kwargs):
        return value
    return _runner


@pytest.fixture(autouse=True)
def reset_runtime_protection():
    runtime_protection_manager.reset()


@pytest.mark.asyncio
async def test_v2_sub_agent_enters_subgraph_and_returns_to_parent():
    registry = ExecutionRegistry()
    scheduler = WorkflowScheduler()
    workflow = {
        "schema_version": "workflow-designer/v2",
        "main_graph_id": "main",
        "graphs": {
            "main": {
                "graph_id": "main",
                "graph_type": "main",
                "entry_node_id": "start_main",
                "nodes": {
                    "start_main": {"id": "start_main", "type": "start", "config": {}},
                    "sub_agent_1": {
                        "id": "sub_agent_1",
                        "type": "sub_agent",
                        "config": {
                            "subgraph_id": "sub_a",
                            "input_mapping": {"request_text": "$execution.user_message"},
                            "output_mapping": {"$execution.done": "$subflow.output.done"},
                        },
                    },
                    "end_main": {
                        "id": "end_main",
                        "type": "end",
                        "config": {"output_format": {"done": "execution.done"}},
                    },
                },
                "edges": [
                    {"id": "e1", "source": "start_main", "target": "sub_agent_1"},
                    {"id": "e2", "source": "sub_agent_1", "target": "end_main"},
                ],
            },
            "sub_a": {
                "graph_id": "sub_a",
                "graph_type": "subflow",
                "entry_node_id": "start_sub",
                "nodes": {
                    "start_sub": {"id": "start_sub", "type": "start", "config": {}},
                    "function_1": {
                        "id": "function_1",
                        "type": "function",
                        "config": {
                            "operation_type": "assign",
                            "assignments": {"done": True},
                        },
                    },
                    "end_sub": {
                        "id": "end_sub",
                        "type": "end",
                        "config": {"output_format": {"done": "execution.done"}},
                    },
                },
                "edges": [
                    {"id": "s1", "source": "start_sub", "target": "function_1"},
                    {"id": "s2", "source": "function_1", "target": "end_sub"},
                ],
            },
        },
    }

    runtime = await registry.create_execution({
        "execution_id": "exec-v2-subgraph",
        "session_id": "session-v2-subgraph",
        "workflow_code": "travel_assistant",
        "workflow_version": "v20260426",
        "workflow_definition": workflow,
        "input_variables": {"user_message": "帮我订一张票"},
    })
    await scheduler.run(runtime)
    events = await _collect_events(runtime)

    assert runtime.context.status == "completed"
    assert runtime.context.execution_variables["done"] is True
    assert [event for event, _ in events].count("graph.entered") == 2
    assert [event for event, _ in events].count("graph.exited") == 2
    assert any(event == "function.executed" for event, _ in events)


@pytest.mark.asyncio
async def test_v2_main_graph_can_finish_from_sub_agent_without_main_end_node():
    registry = ExecutionRegistry()
    scheduler = WorkflowScheduler()
    workflow = {
        "schema_version": "workflow-designer/v2",
        "main_graph_id": "main",
        "graphs": {
            "main": {
                "graph_id": "main",
                "graph_type": "main",
                "entry_node_id": "coordinator_main",
                "nodes": {
                    "coordinator_main": {"id": "coordinator_main", "type": "coordinator", "config": {}},
                    "sub_agent_booking": {
                        "id": "sub_agent_booking",
                        "type": "sub_agent",
                        "config": {
                            "subgraph_id": "sub_booking",
                            "output_mapping": {"$execution.done": "$subflow.output.done"},
                        },
                    },
                },
                "edges": [
                    {"id": "e1", "source": "coordinator_main", "target": "sub_agent_booking"},
                ],
            },
            "sub_booking": {
                "graph_id": "sub_booking",
                "graph_type": "subflow",
                "entry_node_id": "start_sub",
                "nodes": {
                    "start_sub": {"id": "start_sub", "type": "start", "config": {}},
                    "function_1": {
                        "id": "function_1",
                        "type": "function",
                        "config": {
                            "operation_type": "assign",
                            "assignments": {"done": True},
                        },
                    },
                    "end_sub": {
                        "id": "end_sub",
                        "type": "end",
                        "config": {"output_format": {"done": "execution.done"}},
                    },
                },
                "edges": [
                    {"id": "s1", "source": "start_sub", "target": "function_1"},
                    {"id": "s2", "source": "function_1", "target": "end_sub"},
                ],
            },
        },
    }

    runtime = await registry.create_execution({
        "execution_id": "exec-v2-main-leaf-sub-agent",
        "session_id": "session-v2-main-leaf-sub-agent",
        "workflow_code": "travel_assistant",
        "workflow_version": "v20260426",
        "workflow_definition": workflow,
        "input_variables": {"targetNodeId": "sub_agent_booking"},
    })
    await scheduler.run(runtime)
    events = await _collect_events(runtime)

    assert runtime.context.status == "completed"
    assert runtime.context.execution_variables["done"] is True
    assert any(event == "execution.completed" for event, _ in events)


@pytest.mark.asyncio
async def test_v2_coordinator_selects_valid_target_node_id():
    registry = ExecutionRegistry()
    scheduler = WorkflowScheduler()
    workflow = {
        "schema_version": "workflow-designer/v2",
        "main_graph_id": "main",
        "graphs": {
            "main": {
                "graph_id": "main",
                "graph_type": "main",
                "entry_node_id": "start_main",
                "nodes": {
                    "start_main": {"id": "start_main", "type": "start", "config": {}},
                    "coordinator_1": {"id": "coordinator_1", "type": "coordinator", "config": {}},
                    "message_a": {"id": "message_a", "type": "message", "config": {"message_text": "A"}},
                    "message_b": {"id": "message_b", "type": "message", "config": {"message_text": "B"}},
                    "end_main": {"id": "end_main", "type": "end", "config": {}},
                },
                "edges": [
                    {"id": "e1", "source": "start_main", "target": "coordinator_1"},
                    {"id": "e2", "source": "coordinator_1", "target": "message_a"},
                    {"id": "e3", "source": "coordinator_1", "target": "message_b"},
                    {"id": "e4", "source": "message_a", "target": "end_main"},
                    {"id": "e5", "source": "message_b", "target": "end_main"},
                ],
            },
        },
    }

    runtime = await registry.create_execution({
        "execution_id": "exec-v2-coordinator-ok",
        "session_id": "session-v2-coordinator-ok",
        "workflow_code": "travel_assistant",
        "workflow_version": "v20260426",
        "workflow_definition": workflow,
        "input_variables": {"targetNodeId": "message_b"},
    })
    await scheduler.run(runtime)

    assert runtime.context.status == "completed"
    assert "message_b" in runtime.context.completed_nodes
    assert "message_a" not in runtime.context.completed_nodes


@pytest.mark.asyncio
async def test_v2_coordinator_rejects_invalid_target_node_id():
    registry = ExecutionRegistry()
    scheduler = WorkflowScheduler()
    workflow = {
        "schema_version": "workflow-designer/v2",
        "main_graph_id": "main",
        "graphs": {
            "main": {
                "graph_id": "main",
                "graph_type": "main",
                "entry_node_id": "start_main",
                "nodes": {
                    "start_main": {"id": "start_main", "type": "start", "config": {}},
                    "coordinator_1": {"id": "coordinator_1", "type": "coordinator", "config": {}},
                    "message_a": {"id": "message_a", "type": "message", "config": {"message_text": "A"}},
                    "message_b": {"id": "message_b", "type": "message", "config": {"message_text": "B"}},
                    "end_main": {"id": "end_main", "type": "end", "config": {}},
                },
                "edges": [
                    {"id": "e1", "source": "start_main", "target": "coordinator_1"},
                    {"id": "e2", "source": "coordinator_1", "target": "message_a"},
                    {"id": "e3", "source": "coordinator_1", "target": "message_b"},
                    {"id": "e4", "source": "message_a", "target": "end_main"},
                    {"id": "e5", "source": "message_b", "target": "end_main"},
                ],
            },
        },
    }

    runtime = await registry.create_execution({
        "execution_id": "exec-v2-coordinator-bad",
        "session_id": "session-v2-coordinator-bad",
        "workflow_code": "travel_assistant",
        "workflow_version": "v20260426",
        "workflow_definition": workflow,
        "input_variables": {"targetNodeId": "message_x"},
    })
    await scheduler.run(runtime)
    events = await _collect_events(runtime)

    assert runtime.context.status == "failed"
    assert any(
        event == "execution.failed" and "Invalid targetNodeId" in payload.get("error", "")
        for event, payload in events
    )


@pytest.mark.asyncio
async def test_legacy_subflow_keeps_subflow_runtime_semantics():
    registry = ExecutionRegistry()
    scheduler = WorkflowScheduler()
    parent_workflow = {
        "workflow_code": "legacy_parent",
        "workflow_version": "1.0.0",
        "entry": "start",
        "nodes": {
            "start": {"id": "start", "type": "start", "config": {}},
            "run_child": {
                "id": "run_child",
                "type": "subflow",
                "config": {
                    "subflow_code": "legacy_child",
                    "subflow_version": "1.0.0",
                    "output_mapping": {
                        "$execution.done": "$subflow.output.done",
                    },
                },
            },
            "end": {
                "id": "end",
                "type": "end",
                "config": {"output_format": {"done": "execution.done"}},
            },
        },
        "transitions": {
            "start": "run_child",
            "run_child": "end",
            "end": None,
        },
    }
    child_workflow = {
        "workflow_code": "legacy_child",
        "workflow_version": "1.0.0",
        "entry": "start",
        "nodes": {
            "start": {
                "id": "start",
                "type": "start",
                "config": {"initial_variables": {"done": True}},
            },
            "end": {
                "id": "end",
                "type": "end",
                "config": {"output_format": {"done": "execution.done"}},
            },
        },
        "transitions": {
            "start": "end",
            "end": None,
        },
    }

    runtime = await registry.create_execution({
        "execution_id": "exec-legacy-subflow",
        "session_id": "session-legacy-subflow",
        "workflow_code": "legacy_parent",
        "workflow_version": "1.0.0",
        "workflow_definition": parent_workflow,
        "workflow_catalog": {"legacy_child@1.0.0": child_workflow},
    })
    await scheduler.run(runtime)

    assert runtime.context.status == "completed"
    assert runtime.context.get_variable("done") is True
    assert "run_child" in runtime.context.completed_nodes


@pytest.mark.asyncio
async def test_v2_sub_agent_multi_branch_requires_explicit_target_node_id():
    registry = ExecutionRegistry()
    scheduler = WorkflowScheduler()
    workflow = {
        "schema_version": "workflow-designer/v2",
        "main_graph_id": "main",
        "graphs": {
            "main": {
                "graph_id": "main",
                "graph_type": "main",
                "entry_node_id": "start_main",
                "nodes": {
                    "start_main": {"id": "start_main", "type": "start", "config": {}},
                    "sub_agent_1": {
                        "id": "sub_agent_1",
                        "type": "sub_agent",
                        "config": {
                            "subgraph_id": "sub_a",
                            "input_mapping": {},
                            "output_mapping": {"$execution.done": "$subflow.output.done"},
                        },
                    },
                    "message_a": {"id": "message_a", "type": "message", "config": {"message_text": "A"}},
                    "message_b": {"id": "message_b", "type": "message", "config": {"message_text": "B"}},
                    "end_main": {"id": "end_main", "type": "end", "config": {}},
                },
                "edges": [
                    {"id": "e1", "source": "start_main", "target": "sub_agent_1"},
                    {"id": "e2", "source": "sub_agent_1", "target": "message_a"},
                    {"id": "e3", "source": "sub_agent_1", "target": "message_b"},
                    {"id": "e4", "source": "message_a", "target": "end_main"},
                    {"id": "e5", "source": "message_b", "target": "end_main"},
                ],
            },
            "sub_a": {
                "graph_id": "sub_a",
                "graph_type": "subflow",
                "entry_node_id": "start_sub",
                "nodes": {
                    "start_sub": {"id": "start_sub", "type": "start", "config": {}},
                    "function_1": {
                        "id": "function_1",
                        "type": "function",
                        "config": {
                            "operation_type": "assign",
                            "assignments": {"done": True},
                        },
                    },
                    "end_sub": {
                        "id": "end_sub",
                        "type": "end",
                        "config": {"output_format": {"done": "execution.done"}},
                    },
                },
                "edges": [
                    {"id": "s1", "source": "start_sub", "target": "function_1"},
                    {"id": "s2", "source": "function_1", "target": "end_sub"},
                ],
            },
        },
    }

    runtime = await registry.create_execution({
        "execution_id": "exec-v2-sub-agent-missing-target",
        "session_id": "session-v2-sub-agent-missing-target",
        "workflow_code": "travel_assistant",
        "workflow_version": "v20260426",
        "workflow_definition": workflow,
    })
    await scheduler.run(runtime)
    events = await _collect_events(runtime)

    assert runtime.context.status == "failed"
    assert any(
        event == "execution.failed" and "must explicitly return targetNodeId" in payload.get("error", "")
        for event, payload in events
    )


@pytest.mark.asyncio
async def test_v2_runtime_emits_cost_chain_and_completion_metrics(monkeypatch):
    registry = ExecutionRegistry()
    scheduler = WorkflowScheduler()
    workflow = {
        "schema_version": "workflow-designer/v2",
        "main_graph_id": "main",
        "graphs": {
            "main": {
                "graph_id": "main",
                "graph_type": "main",
                "entry_node_id": "start_main",
                "nodes": {
                    "start_main": {"id": "start_main", "type": "start", "config": {}},
                    "extract_slots": {
                        "id": "extract_slots",
                        "type": "llm",
                        "config": {
                            "prompt": "slot_extraction",
                            "structured_output": {
                                "enabled": True,
                                "schema": {
                                    "type": "object",
                                    "properties": {
                                        "intent": {"type": "string"},
                                    },
                                },
                            },
                        },
                    },
                    "end_main": {
                        "id": "end_main",
                        "type": "end",
                        "config": {"output_format": {"intent": "execution.intent"}},
                    },
                },
                "edges": [
                    {"id": "e1", "source": "start_main", "target": "extract_slots"},
                    {"id": "e2", "source": "extract_slots", "target": "end_main"},
                ],
            },
        },
    }
    monkeypatch.setattr(
        "src.nodes.llm.execute_model_completion",
        async_result('{"intent":"book_flight"}'),
    )
    monkeypatch.setattr(
        "src.core.scheduler.budget_alert_evaluator.evaluate",
        lambda workflow_code, user_id, total_cost: [
            BudgetAlert(
                scope="workflow",
                scope_id=workflow_code,
                total_cost=total_cost,
                threshold=0.0,
                message="forced alert",
            )
        ],
    )

    runtime = await registry.create_execution({
        "execution_id": "exec-v2-cost",
        "session_id": "session-v2-cost",
        "workflow_code": "travel_assistant",
        "workflow_version": "v20260426",
        "workflow_definition": workflow,
        "workflow_config": {
            "llm_defaults": {"model_code": "general-chat-v1"},
        },
        "provider_configs": [
            {
                "provider_code": "openai-compatible-prod",
                "provider_type": "openai_compatible",
                "base_url": "https://llm.example.com/v1",
            },
        ],
        "model_records": [
            {
                "model_code": "general-chat-v1",
                "provider_code": "openai-compatible-prod",
                "upstream_model_code": "gpt-4.1-mini",
            },
        ],
        "input_variables": {"user_message": "我要从北京去上海"},
    })
    await scheduler.run(runtime)
    events = await _collect_events(runtime)

    cost_events = [payload for event, payload in events if event == "cost.recorded"]
    budget_events = [payload for event, payload in events if event == "budget.alert"]
    completion_events = [payload for event, payload in events if event == "execution.completed"]

    assert runtime.context.status == "completed"
    assert len(cost_events) >= 1
    assert len(budget_events) >= 1
    assert len(completion_events) == 1
    assert completion_events[0]["metrics"]["total_cost"] > 0
    assert completion_events[0]["metrics"]["input_tokens"] > 0


@pytest.mark.asyncio
async def test_v2_second_multibranch_node_does_not_reuse_previous_target_node_id():
    registry = ExecutionRegistry()
    scheduler = WorkflowScheduler()
    workflow = {
        "schema_version": "workflow-designer/v2",
        "main_graph_id": "main",
        "graphs": {
            "main": {
                "graph_id": "main",
                "graph_type": "main",
                "entry_node_id": "start_main",
                "nodes": {
                    "start_main": {"id": "start_main", "type": "start", "config": {}},
                    "coordinator_1": {"id": "coordinator_1", "type": "coordinator", "config": {}},
                    "coordinator_2": {"id": "coordinator_2", "type": "coordinator", "config": {}},
                    "message_a": {"id": "message_a", "type": "message", "config": {"message_text": "A"}},
                    "message_b": {"id": "message_b", "type": "message", "config": {"message_text": "B"}},
                    "end_main": {"id": "end_main", "type": "end", "config": {}},
                },
                "edges": [
                    {"id": "e1", "source": "start_main", "target": "coordinator_1"},
                    {"id": "e2", "source": "coordinator_1", "target": "coordinator_2"},
                    {"id": "e3", "source": "coordinator_1", "target": "end_main"},
                    {"id": "e4", "source": "coordinator_2", "target": "message_a"},
                    {"id": "e5", "source": "coordinator_2", "target": "message_b"},
                    {"id": "e6", "source": "message_a", "target": "end_main"},
                    {"id": "e7", "source": "message_b", "target": "end_main"},
                ],
            },
        },
    }

    runtime = await registry.create_execution({
        "execution_id": "exec-v2-two-coordinators",
        "session_id": "session-v2-two-coordinators",
        "workflow_code": "travel_assistant",
        "workflow_version": "v20260426",
        "workflow_definition": workflow,
        "input_variables": {
            "targetNodeId": "coordinator_2",
        },
    })
    await scheduler.run(runtime)
    events = await _collect_events(runtime)

    assert runtime.context.status == "failed"
    assert any(
        event == "execution.failed"
        and "coordinator_2 must return targetNodeId when multiple targets exist" in payload.get("error", "")
        for event, payload in events
    )
