import asyncio
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
async def test_v2_start_node_missing_declared_input_waits_and_resumes():
    registry = ExecutionRegistry()
    scheduler = WorkflowScheduler()
    workflow = {
        "schema_version": "workflow-designer/v2",
        "main_graph_id": "main",
        "graphs": {
            "main": {
                "graph_id": "main",
                "graph_type": "main",
                "entry_node_id": "start",
                "nodes": {
                    "start": {
                        "id": "start",
                        "type": "start",
                        "config": {
                            "prompt": "缺少出行城市时需要向用户询问。",
                            "initial_variables": {"city": "", "priority": "普通"},
                            "input_variables": [
                                {"name": "city", "type": "string", "description": "用户要去的城市", "default": ""},
                                {"name": "priority", "type": "string", "description": "服务优先级", "default": "普通"},
                            ],
                        },
                    },
                    "message": {"id": "message", "type": "message", "config": {"message_text": "继续执行"}},
                    "end": {"id": "end", "type": "end", "config": {"output_format": {"city": "execution.city"}}},
                },
                "edges": [
                    {"id": "e1", "source": "start", "target": "message"},
                    {"id": "e2", "source": "message", "target": "end"},
                ],
            },
        },
    }

    runtime = await registry.create_execution({
        "execution_id": "exec-v2-start-input-wait",
        "session_id": "session-v2-start-input-wait",
        "workflow_code": "travel",
        "workflow_version": "v1",
        "workflow_definition": workflow,
        "input_variables": {"user_message": "帮我安排一下"},
    })
    run_task = asyncio.create_task(scheduler.run(runtime))
    for _ in range(100):
        if runtime.context.status == "waiting_user":
            break
        await asyncio.sleep(0.01)
    runtime.resume({"city": "上海"})
    await run_task
    events = await _collect_events(runtime)

    assert runtime.context.status == "completed"
    assert runtime.context.execution_variables["city"] == "上海"
    assert runtime.context.execution_variables["priority"] == "普通"
    form_events = [payload for event, payload in events if event == "form.requested"]
    message_events = [payload for event, payload in events if event == "message.delta"]
    waiting_events = [payload for event, payload in events if event == "execution.waiting_user"]
    assert form_events == []
    assert message_events[0]["content"] == "请提供用户要去的城市。"
    assert "city" not in message_events[0]["content"]
    assert waiting_events[0]["reason"] == "start_input_variables_missing"


@pytest.mark.asyncio
async def test_v2_start_node_extracts_slot_from_chat_reply_after_wait(monkeypatch):
    calls = []

    async def fake_completion(**kwargs):
        payload = json.loads(kwargs["user_prompt"])
        calls.append(payload["user_message"])
        if "上海" in payload["user_message"]:
            return json.dumps({"variables": {"city": "上海"}}, ensure_ascii=False)
        return json.dumps({"variables": {}, "missing_fields": ["city"]}, ensure_ascii=False)

    monkeypatch.setattr("src.nodes.start.execute_model_completion", fake_completion)
    registry = ExecutionRegistry()
    scheduler = WorkflowScheduler()
    workflow = {
        "schema_version": "workflow-designer/v2",
        "main_graph_id": "main",
        "graphs": {
            "main": {
                "graph_id": "main",
                "graph_type": "main",
                "entry_node_id": "start",
                "nodes": {
                    "start": {
                        "id": "start",
                        "type": "start",
                        "config": {
                            "prompt": "缺少出行城市时需要向用户询问。",
                            "initial_variables": {"city": ""},
                            "input_variables": [
                                {"name": "city", "type": "string", "description": "用户要去的城市", "default": ""},
                            ],
                        },
                    },
                    "message": {"id": "message", "type": "message", "config": {"message_text": "继续执行"}},
                    "end": {"id": "end", "type": "end", "config": {}},
                },
                "edges": [
                    {"id": "e1", "source": "start", "target": "message"},
                    {"id": "e2", "source": "message", "target": "end"},
                ],
            },
        },
    }

    runtime = await registry.create_execution({
        "execution_id": "exec-v2-start-chat-reply",
        "session_id": "session-v2-start-chat-reply",
        "workflow_code": "travel",
        "workflow_version": "v1",
        "workflow_definition": workflow,
        "workflow_config": {"llm_defaults": {"model_code": "slot-model"}},
        "provider_configs": [{"provider_code": "test-provider"}],
        "model_records": [{"model_code": "slot-model", "provider_code": "test-provider"}],
        "input_variables": {"user_message": "帮我安排一下"},
    })
    run_task = asyncio.create_task(scheduler.run(runtime))
    for _ in range(100):
        if runtime.context.status == "waiting_user":
            break
        await asyncio.sleep(0.01)
    runtime.resume({"user_message": "我的目的地是上海"})
    await run_task

    assert runtime.context.status == "completed"
    assert runtime.context.execution_variables["city"] == "上海"
    assert calls == ["帮我安排一下", "我的目的地是上海"]


@pytest.mark.asyncio
async def test_v2_start_node_uses_workflow_variable_descriptions_for_slot_question():
    registry = ExecutionRegistry()
    scheduler = WorkflowScheduler()
    workflow = {
        "schema_version": "workflow-designer/v2",
        "main_graph_id": "main",
        "variables": {
            "global": [
                {"name": "id", "type": "string", "description": "您的身份证号"},
                {"name": "room_type", "type": "string", "description": "房间类型"},
                {"name": "startDate", "type": "date", "description": "入住日期"},
                {"name": "departmentDate", "type": "date", "description": "离开日期"},
            ]
        },
        "graphs": {
            "main": {
                "graph_id": "main",
                "graph_type": "main",
                "entry_node_id": "start",
                "nodes": {
                    "start": {
                        "id": "start",
                        "type": "start",
                        "config": {
                            "prompt": "收集酒店预订信息。",
                            "initial_variables": {
                                "id": "",
                                "room_type": "",
                                "startDate": "",
                                "departmentDate": "",
                            },
                        },
                    },
                    "end": {"id": "end", "type": "end", "config": {}},
                },
                "edges": [{"id": "e1", "source": "start", "target": "end"}],
            },
        },
    }

    runtime = await registry.create_execution({
        "execution_id": "exec-v2-start-description-question",
        "session_id": "session-v2-start-description-question",
        "workflow_code": "hotel",
        "workflow_version": "v1",
        "workflow_definition": workflow,
        "input_variables": {"user_message": "我要订酒店"},
    })
    run_task = asyncio.create_task(scheduler.run(runtime))
    for _ in range(100):
        if runtime.context.status == "waiting_user":
            break
        await asyncio.sleep(0.01)
    runtime.resume({
        "id": "310101199001011234",
        "room_type": "大床房",
        "startDate": "2026-06-01",
        "departmentDate": "2026-06-03",
    })
    await run_task
    events = await _collect_events(runtime)

    message_events = [payload for event, payload in events if event == "message.delta"]
    question = message_events[0]["content"]
    assert "您的身份证号" in question
    assert "房间类型" in question
    assert "入住日期" in question
    assert "离开日期" in question
    assert "room_type" not in question
    assert "startDate" not in question
    assert "departmentDate" not in question


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
async def test_v2_sub_agent_multi_branch_uses_internal_react_decision_when_target_missing():
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

    branch_events = [payload for event, payload in events if event == "branch.decided"]
    assert runtime.context.status == "completed"
    assert any(payload.get("targetNodeId") == "message_a" for payload in branch_events)
    assert "message_a" in runtime.context.completed_nodes


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
async def test_legacy_multibranch_llm_uses_internal_react_decision(monkeypatch):
    registry = ExecutionRegistry()
    scheduler = WorkflowScheduler()
    workflow = {
        "workflow_code": "flight_booking",
        "workflow_version": "2.0.0",
        "entry": "start",
        "nodes": {
            "start": {"id": "start", "type": "start", "config": {"initial_variables": {}}},
            "extract_slots": {
                "id": "extract_slots",
                "type": "llm",
                "config": {
                    "prompt": "slot_extraction",
                    "structured_output": {"enabled": True, "schema": {"type": "object", "properties": {}}},
                },
            },
            "collect_info": {
                "id": "collect_info",
                "type": "form",
                "config": {
                    "title": "Complete trip info",
                    "fields": [
                        {"name": "departure_city", "type": "text", "required": True},
                        {"name": "arrival_city", "type": "text", "required": True},
                        {"name": "departure_date", "type": "date", "required": True},
                    ],
                },
            },
            "search_flights": {
                "id": "search_flights",
                "type": "message",
                "config": {"message_text": "search ready"},
            },
            "end": {"id": "end", "type": "end", "config": {}},
        },
        "transitions": {
            "start": "extract_slots",
            "extract_slots": {"missing": "collect_info", "complete": "search_flights"},
            "collect_info": "search_flights",
            "search_flights": "end",
            "end": None,
        },
    }
    monkeypatch.setattr(
        "src.nodes.llm.execute_model_completion",
        async_result('{"departure_city":"北京"}'),
    )

    runtime = await registry.create_execution({
        "execution_id": "exec-legacy-react-missing",
        "session_id": "session-legacy-react-missing",
        "workflow_code": "flight_booking",
        "workflow_version": "2.0.0",
        "workflow_definition": workflow,
        "workflow_config": {"decision_policy": {"mode": "rules"}, "llm_defaults": {"model_code": "general-chat-v1"}},
        "provider_configs": [{"provider_code": "test-provider", "provider_type": "openai_compatible", "base_url": "https://llm.example.com/v1"}],
        "model_records": [{"model_code": "general-chat-v1", "provider_code": "test-provider", "upstream_model_code": "gpt-test"}],
        "input_variables": {"user_message": "我要订机票"},
    })
    run_task = asyncio.create_task(scheduler.run(runtime))
    for _ in range(100):
        if runtime.last_form_definition:
            break
        await asyncio.sleep(0.01)
    runtime.resume({"arrival_city": "上海", "departure_date": "2026-06-01"})
    await run_task
    events = await _collect_events(runtime)

    branch_events = [payload for event, payload in events if event == "branch.decided"]
    assert runtime.context.status == "completed"
    assert branch_events[0]["targetNodeId"] == "collect_info"
    assert branch_events[0]["action"] == "ask_user"
    assert branch_events[0]["missing_fields"] == ["arrival_city", "departure_date"]
    assert "search_flights" in runtime.context.completed_nodes


@pytest.mark.asyncio
async def test_v2_multibranch_llm_uses_internal_react_decision(monkeypatch):
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
                            "structured_output": {"enabled": True, "schema": {"type": "object", "properties": {}}},
                        },
                    },
                    "collect_info": {
                        "id": "collect_info",
                        "type": "form",
                        "config": {
                            "title": "Complete trip info",
                            "fields": [
                                {"name": "departure_city", "type": "text", "required": True},
                                {"name": "arrival_city", "type": "text", "required": True},
                                {"name": "departure_date", "type": "date", "required": True},
                            ],
                        },
                    },
                    "search_flights": {"id": "search_flights", "type": "message", "config": {"message_text": "search ready"}},
                    "end_main": {"id": "end_main", "type": "end", "config": {}},
                },
                "edges": [
                    {"id": "e1", "source": "start_main", "target": "extract_slots"},
                    {"id": "e2", "source": "extract_slots", "target": "collect_info", "label": "missing"},
                    {"id": "e3", "source": "extract_slots", "target": "search_flights", "label": "complete"},
                    {"id": "e4", "source": "collect_info", "target": "search_flights"},
                    {"id": "e5", "source": "search_flights", "target": "end_main"},
                ],
            },
        },
    }
    monkeypatch.setattr(
        "src.nodes.llm.execute_model_completion",
        async_result('{"departure_city":"北京"}'),
    )

    runtime = await registry.create_execution({
        "execution_id": "exec-v2-react-missing",
        "session_id": "session-v2-react-missing",
        "workflow_code": "flight_booking",
        "workflow_version": "2.0.0",
        "workflow_definition": workflow,
        "workflow_config": {"decision_policy": {"mode": "rules"}, "llm_defaults": {"model_code": "general-chat-v1"}},
        "provider_configs": [{"provider_code": "test-provider", "provider_type": "openai_compatible", "base_url": "https://llm.example.com/v1"}],
        "model_records": [{"model_code": "general-chat-v1", "provider_code": "test-provider", "upstream_model_code": "gpt-test"}],
        "input_variables": {"user_message": "我要订机票"},
    })
    run_task = asyncio.create_task(scheduler.run(runtime))
    for _ in range(100):
        if runtime.last_form_definition:
            break
        await asyncio.sleep(0.01)
    runtime.resume({"arrival_city": "上海", "departure_date": "2026-06-01"})
    await run_task
    events = await _collect_events(runtime)

    branch_events = [payload for event, payload in events if event == "branch.decided"]
    assert runtime.context.status == "completed"
    assert branch_events[0]["targetNodeId"] == "collect_info"
    assert branch_events[0]["action"] == "ask_user"
    assert branch_events[0]["missing_fields"] == ["arrival_city", "departure_date"]
    assert "search_flights" in runtime.context.completed_nodes


@pytest.mark.asyncio
async def test_v2_condition_branch_uses_workflow_next_without_react_or_tool(monkeypatch):
    async def fail_react(*_args, **_kwargs):
        raise AssertionError("ReAct should not run when condition node already selected next_node")

    monkeypatch.setattr("src.core.scheduler.ReactDecisionService.decide_next_node", fail_react)
    registry = ExecutionRegistry()
    scheduler = WorkflowScheduler()
    workflow = {
        "schema_version": "workflow-designer/v2",
        "main_graph_id": "main",
        "graphs": {
            "main": {
                "graph_id": "main",
                "graph_type": "main",
                "entry_node_id": "start",
                "nodes": {
                    "start": {"id": "start", "type": "start", "config": {}},
                    "check_slots": {
                        "id": "check_slots",
                        "type": "condition",
                        "config": {
                            "required_fields": ["departure_city", "arrival_city"],
                        },
                    },
                    "collect_info": {
                        "id": "collect_info",
                        "type": "message",
                        "config": {"message_text": "请补充到达城市。"},
                    },
                    "search_flights": {
                        "id": "search_flights",
                        "type": "tool",
                        "config": {"tool_code": "flight_search_api"},
                    },
                    "done": {"id": "done", "type": "end", "config": {}},
                },
                "edges": [
                    {"id": "e1", "source": "start", "target": "check_slots"},
                    {"id": "e2", "source": "check_slots", "target": "collect_info", "branch": "missing"},
                    {"id": "e3", "source": "check_slots", "target": "done", "branch": "complete"},
                    {"id": "e4", "source": "check_slots", "target": "search_flights", "branch": "react_only"},
                    {"id": "e5", "source": "collect_info", "target": "done"},
                    {"id": "e6", "source": "search_flights", "target": "done"},
                ],
            }
        },
    }

    runtime = await registry.create_execution({
        "execution_id": "exec-v2-condition-deterministic",
        "session_id": "session-v2-condition-deterministic",
        "workflow_code": "flight_booking",
        "workflow_version": "custom",
        "workflow_definition": workflow,
        "input_variables": {"departure_city": "北京"},
    })
    await scheduler.run(runtime)

    assert runtime.context.status == "completed"
    assert "collect_info" in runtime.context.completed_nodes
    assert "search_flights" not in runtime.context.completed_nodes


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
