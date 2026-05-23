import logging
import time
from typing import Any, Dict, List, Optional

from .context_assembler import ContextAssembler
from .costing import budget_alert_evaluator
from .events import utc_now_iso
from .journal import WorkflowJournal
from .node_readiness import NodeReadinessChecker
from .path_resolver import PathResolver
from .planner import Planner
from .protection import ConfirmationRequiredError
from .react_decision import ReactDecisionService
from .replanner import RePlanner
from .runtime import ExecutionRuntime
from .security import InvalidOutputError
from .state_machine import ExecutionStateMachine, TransitionEvent
from .telemetry import workflow_telemetry
from src.nodes import (
    ConditionNode,
    CoordinatorNode,
    EndNode,
    FormNode,
    FunctionNode,
    KnowledgeNode,
    LLMNode,
    MessageNode,
    StartNode,
    SubflowNode,
    ToolNode,
)


class WorkflowScheduler:
    """Workflow scheduler with planning, path resolution and node readiness checks."""

    def __init__(self):
        self.logger = logging.getLogger(__name__)
        self.context_assembler = ContextAssembler()
        self.planner = Planner()
        self.path_resolver = PathResolver()
        self.node_readiness_checker = NodeReadinessChecker()
        self.react_decision_service = ReactDecisionService()
        self.replanner = RePlanner()
        self.workflow_journal = WorkflowJournal()

    async def run(self, runtime: ExecutionRuntime) -> None:
        context = runtime.context
        workflow = runtime.workflow
        state_machine = ExecutionStateMachine()
        state_machine.set_context(context)
        self.logger.info(
            "scheduler.run.start sessionId=%s executionId=%s workflowCode=%s workflowVersion=%s routeDecision=%s graphV2=%s",
            context.session_id,
            context.execution_id,
            context.workflow_code,
            context.workflow_version,
            context.route_decision,
            self._is_graph_definition_v2(workflow),
        )

        state_machine.transition(TransitionEvent.START)
        self.logger.info(
            "scheduler.state.transition sessionId=%s executionId=%s event=%s status=%s",
            context.session_id,
            context.execution_id,
            TransitionEvent.START.value,
            context.status,
        )
        runtime.emit("routing.decided", {
            "execution_id": context.execution_id,
            "session_id": context.session_id,
            "workflow_code": context.workflow_code,
            "workflow_version": context.workflow_version,
            "decision": context.route_decision,
            "reason": context.route_reason,
            "confidence": context.route_confidence,
            "priority": context.priority,
            "trace_id": context.trace_id,
        })
        state_machine.transition(TransitionEvent.ROUTE)
        self.logger.info(
            "scheduler.state.transition sessionId=%s executionId=%s event=%s status=%s",
            context.session_id,
            context.execution_id,
            TransitionEvent.ROUTE.value,
            context.status,
        )
        total_cost = 0.0
        total_input_tokens = 0
        total_output_tokens = 0
        models_used: set[str] = set()
        runtime.emit("execution.started", {
            "execution_id": context.execution_id,
            "session_id": context.session_id,
            "started_at": utc_now_iso(),
            "status": context.status,
            "workflow_code": context.workflow_code,
            "workflow_version": context.workflow_version,
            "priority": context.priority,
            "experiment_id": context.experiment_id,
            "experiment_group": context.experiment_group,
            "dynamic_threshold": context.dynamic_threshold,
            "threshold_source": context.threshold_source,
            "trace_id": context.trace_id,
        })

        current_node_id = workflow.get("entry")

        try:
            if self._is_graph_definition_v2(workflow):
                graph_runtime_metrics = await self._run_graph_runtime_v2(runtime)
                total_cost = float(graph_runtime_metrics.get("total_cost", 0.0))
                total_input_tokens = int(graph_runtime_metrics.get("input_tokens", 0))
                total_output_tokens = int(graph_runtime_metrics.get("output_tokens", 0))
                models_used = set(graph_runtime_metrics.get("models_used", []))
                current_node_id = None

            while current_node_id:
                suspend_reason = runtime.consume_suspend_request()
                if suspend_reason:
                    self.logger.info(
                        "scheduler.suspend.detected sessionId=%s executionId=%s nodeId=%s reason=%s",
                        context.session_id,
                        context.execution_id,
                        current_node_id,
                        suspend_reason,
                    )
                    await self._suspend_execution(runtime, state_machine, suspend_reason)

                self.logger.info(
                    "scheduler.plan.prepare sessionId=%s executionId=%s nodeId=%s nextPlanRound=%s",
                    context.session_id,
                    context.execution_id,
                    current_node_id,
                    context.plan_round + 1,
                )
                planning_context = self.context_assembler.build(context, workflow, current_node_id)
                plan = self.planner.plan(planning_context)
                path_decision = self.path_resolver.resolve(plan, workflow, context)
                if not path_decision.selected_node:
                    self.logger.warning(
                        "scheduler.plan.no_selected_node sessionId=%s executionId=%s currentNodeId=%s candidateNodes=%s",
                        context.session_id,
                        context.execution_id,
                        current_node_id,
                        plan.candidate_nodes,
                    )
                    raise ValueError(f"No resolvable node for workflow {context.workflow_code}")

                self.logger.info(
                    "scheduler.plan.resolved sessionId=%s executionId=%s selectedNode=%s candidateNodes=%s reason=%s confidence=%s",
                    context.session_id,
                    context.execution_id,
                    path_decision.selected_node,
                    plan.candidate_nodes,
                    path_decision.reason,
                    plan.confidence,
                )
                context.record_plan(plan.to_dict())
                self.workflow_journal.record_plan({
                    **plan.to_dict(),
                    "selected_node": path_decision.selected_node,
                    "path_reason": path_decision.reason,
                })
                runtime.emit("plan.created" if context.plan_round == 1 else "plan.replanned", {
                    "execution_id": context.execution_id,
                    "session_id": context.session_id,
                    "workflow_code": context.workflow_code,
                    "workflow_version": context.workflow_version,
                    "selected_node": path_decision.selected_node,
                    "candidate_nodes": plan.candidate_nodes,
                    "reason": plan.reasoning_summary,
                    "confidence": plan.confidence,
                    "need_user_input": plan.need_user_input,
                    "missing_fields": plan.missing_fields,
                    "trace_id": context.trace_id,
                })

                current_node_id = path_decision.selected_node
                context.update_node_position(current_node_id)
                node_def = workflow["nodes"].get(current_node_id)
                if not node_def:
                    self.logger.warning(
                        "scheduler.node.missing sessionId=%s executionId=%s nodeId=%s",
                        context.session_id,
                        context.execution_id,
                        current_node_id,
                    )
                    raise ValueError(f"Node not found: {current_node_id}")

                readiness = self.node_readiness_checker.check(node_def, context, workflow)
                if readiness.should_skip:
                    self.logger.info(
                        "scheduler.node.skipped sessionId=%s executionId=%s nodeId=%s nodeType=%s reason=%s nextNode=%s",
                        context.session_id,
                        context.execution_id,
                        node_def["id"],
                        node_def["type"],
                        readiness.reason,
                        readiness.next_node,
                    )
                    context.record_skipped_node(node_def["id"], readiness.reason)
                    self.workflow_journal.record_skip(node_def["id"], readiness.reason)
                    runtime.emit("node.skipped", {
                        "execution_id": context.execution_id,
                        "session_id": context.session_id,
                        "node_id": node_def["id"],
                        "node_type": node_def["type"],
                        "reason": readiness.reason,
                        "next_node": readiness.next_node,
                        "trace_id": context.trace_id,
                    })
                    current_node_id = readiness.next_node
                    continue

                node = self._build_node(node_def)
                self.logger.info(
                    "scheduler.node.start sessionId=%s executionId=%s nodeId=%s nodeType=%s",
                    context.session_id,
                    context.execution_id,
                    node_def["id"],
                    node_def["type"],
                )
                runtime.emit("node.started", {
                    "execution_id": context.execution_id,
                    "session_id": context.session_id,
                    "node_id": node_def["id"],
                    "node_type": node_def["type"],
                    "started_at": utc_now_iso(),
                    "trace_id": context.trace_id,
                })

                started_at = time.perf_counter()
                with workflow_telemetry.span("execute_node", {
                    "node.id": node_def["id"],
                    "node.type": node_def["type"],
                    "execution.id": context.execution_id,
                    "workflow.code": context.workflow_code,
                }):
                    result = await node.execute(context)
                duration_ms = int((time.perf_counter() - started_at) * 1000)
                self.logger.info(
                    "scheduler.node.result sessionId=%s executionId=%s nodeId=%s nodeType=%s durationMs=%s resultKeys=%s outputKeys=%s",
                    context.session_id,
                    context.execution_id,
                    node_def["id"],
                    node_def["type"],
                    duration_ms,
                    sorted(result.keys()) if isinstance(result, dict) else [],
                    sorted(result.get("output", {}).keys()) if isinstance(result.get("output"), dict) else [],
                )

                for security_event in result.get("security_events", []):
                    runtime.emit(security_event["event_type"], {
                        "execution_id": context.execution_id,
                        "session_id": context.session_id,
                        "node_id": node_def["id"],
                        **security_event.get("data", {}),
                        "trace_id": context.trace_id,
                    })
                for protection_event in result.get("protection_events", []):
                    runtime.emit(protection_event["event_type"], {
                        "execution_id": context.execution_id,
                        "session_id": context.session_id,
                        "node_id": node_def["id"],
                        **protection_event.get("data", {}),
                        "trace_id": context.trace_id,
                    })

                next_node_id = await self._next_node(workflow, node_def, result, context)
                self.logger.info(
                    "scheduler.branch.resolved sessionId=%s executionId=%s nodeId=%s nextNode=%s",
                    context.session_id,
                    context.execution_id,
                    node_def["id"],
                    next_node_id,
                )
                transition_candidates = self._legacy_transition_candidates(
                    workflow,
                    node_def["id"],
                    workflow.get("transitions", {}).get(node_def["id"]),
                )
                if node_def["type"] == "condition" or len(transition_candidates) > 1:
                    decision_payload = self._react_decision_payload(result)
                    runtime.emit("branch.decided", {
                        "execution_id": context.execution_id,
                        "session_id": context.session_id,
                        "node_id": node_def["id"],
                        "node_type": node_def["type"],
                        "candidates": [candidate["target_node_id"] for candidate in transition_candidates],
                        "branch": result.get("branch"),
                        "condition_met": result.get("condition_met"),
                        "missing_fields": decision_payload.get("missing_fields", result.get("missing_fields", [])),
                        "action": decision_payload.get("action"),
                        "reason": decision_payload.get("reason_summary"),
                        "confidence": decision_payload.get("confidence"),
                        "targetNodeId": next_node_id,
                        "next_node": next_node_id,
                        "trace_id": context.trace_id,
                    })

                if node_def["type"] == "form":
                    runtime.last_form_definition = result.get("form_definition", {})
                    state_machine.transition(TransitionEvent.WAIT_USER)
                    self.logger.info(
                        "scheduler.form.waiting sessionId=%s executionId=%s nodeId=%s formKeys=%s status=%s",
                        context.session_id,
                        context.execution_id,
                        node_def["id"],
                        sorted(runtime.last_form_definition.keys()) if isinstance(runtime.last_form_definition, dict) else [],
                        context.status,
                    )
                    runtime.emit("form.requested", {
                        "execution_id": context.execution_id,
                        "session_id": context.session_id,
                        "node_id": node_def["id"],
                        "form_definition": runtime.last_form_definition,
                        "trace_id": context.trace_id,
                    })
                    runtime.emit("execution.waiting_user", {
                        "execution_id": context.execution_id,
                        "session_id": context.session_id,
                        "reason": "form_requested",
                        "status": context.status,
                        "trace_id": context.trace_id,
                    })
                    runtime.prepare_wait()
                    form_data = await runtime.wait_for_resume()
                    context.add_execution_variables(form_data)
                    state_machine.transition(TransitionEvent.RESUME)
                    self.logger.info(
                        "scheduler.form.resumed sessionId=%s executionId=%s nodeId=%s formKeys=%s status=%s",
                        context.session_id,
                        context.execution_id,
                        node_def["id"],
                        sorted((form_data or {}).keys()),
                        context.status,
                    )
                    runtime.emit("execution.resumed", {
                        "execution_id": context.execution_id,
                        "session_id": context.session_id,
                        "status": context.status,
                        "resume_type": "form_submit",
                        "trace_id": context.trace_id,
                    })
                else:
                    if result.get("tool_called"):
                        called = result["tool_called"]
                        runtime.emit("tool.called", {
                            "execution_id": context.execution_id,
                            "session_id": context.session_id,
                            "node_id": node_def["id"],
                            "tool_code": called.get("tool_code"),
                            "params": called.get("params", {}),
                            "group_code": called.get("group_code"),
                            "group_snapshot_version": called.get("group_snapshot_version"),
                            "capability_code": called.get("capability_code"),
                            "capability_version": called.get("capability_version"),
                            "capability_type": called.get("capability_type"),
                            "trace_id": context.trace_id,
                        })
                    if result.get("tool_returned"):
                        returned = result["tool_returned"]
                        runtime.emit("tool.returned", {
                            "execution_id": context.execution_id,
                            "session_id": context.session_id,
                            "node_id": node_def["id"],
                            "tool_code": returned.get("tool_code"),
                            "params": returned.get("params", {}),
                            "output": returned.get("output", {}),
                            "error": returned.get("error"),
                            "group_code": returned.get("group_code"),
                            "group_snapshot_version": returned.get("group_snapshot_version"),
                            "capability_code": returned.get("capability_code"),
                            "capability_version": returned.get("capability_version"),
                            "capability_type": returned.get("capability_type"),
                            "status": "completed",
                            "trace_id": context.trace_id,
                        })
                    for delta in result.get("message_deltas", []):
                        runtime.emit("message.delta", {
                            "execution_id": context.execution_id,
                            "session_id": context.session_id,
                            "content": delta,
                            "delta_type": "text",
                        })

                metrics = dict(result.get("metrics", {}))
                metrics.setdefault("duration_ms", duration_ms)
                metrics.setdefault("trace_id", context.trace_id)
                if "cost" in metrics:
                    total_cost += float(metrics.get("cost", 0.0))
                    total_input_tokens += int(metrics.get("input_tokens", 0))
                    total_output_tokens += int(metrics.get("output_tokens", 0))
                    if metrics.get("model"):
                        models_used.add(str(metrics["model"]))
                        workflow_telemetry.record_llm_cost(
                            str(metrics["model"]),
                            context.workflow_code,
                            int(metrics.get("input_tokens", 0)),
                            int(metrics.get("output_tokens", 0)),
                            float(metrics.get("cost", 0.0)),
                        )
                    runtime.emit("cost.recorded", {
                        "execution_id": context.execution_id,
                        "session_id": context.session_id,
                        "workflow_code": context.workflow_code,
                        "workflow_version": context.workflow_version,
                        "experiment_id": context.experiment_id,
                        "experiment_group": context.experiment_group,
                        **metrics,
                        "trace_id": context.trace_id,
                    })
                    for alert in budget_alert_evaluator.evaluate(context.workflow_code, context.user_id, total_cost):
                        runtime.emit("budget.alert", {
                            "execution_id": context.execution_id,
                            "session_id": context.session_id,
                            "scope": alert.scope,
                            "scope_id": alert.scope_id,
                            "total_cost": alert.total_cost,
                            "threshold": alert.threshold,
                            "message": alert.message,
                            "trace_id": context.trace_id,
                        })

                output = result.get("output", {})
                context.record_completed_node(node_def["id"], output if isinstance(output, dict) else {})
                self.workflow_journal.record_execution(
                    node_def["id"],
                    "completed",
                    output if isinstance(output, dict) else {},
                )
                runtime.emit("node.completed", {
                    "execution_id": context.execution_id,
                    "session_id": context.session_id,
                    "node_id": node_def["id"],
                    "node_type": node_def["type"],
                    "status": "completed",
                    "output": output,
                    "metrics": metrics,
                    "trace_id": context.trace_id,
                })
                workflow_telemetry.record_node(context.workflow_code, node_def["type"], "completed", duration_ms)
                self.logger.info(
                    "scheduler.node.completed sessionId=%s executionId=%s nodeId=%s nodeType=%s nextNode=%s durationMs=%s totalCost=%.6f",
                    context.session_id,
                    context.execution_id,
                    node_def["id"],
                    node_def["type"],
                    next_node_id,
                    duration_ms,
                    total_cost,
                )

                suspend_reason = runtime.consume_suspend_request()
                if suspend_reason:
                    self.logger.info(
                        "scheduler.suspend.detected_after_node sessionId=%s executionId=%s nodeId=%s reason=%s",
                        context.session_id,
                        context.execution_id,
                        node_def["id"],
                        suspend_reason,
                    )
                    await self._suspend_execution(runtime, state_machine, suspend_reason)

                if self.replanner.should_replan(result, node_def):
                    self.logger.info(
                        "scheduler.replan.requested sessionId=%s executionId=%s nodeId=%s nextNode=%s",
                        context.session_id,
                        context.execution_id,
                        node_def["id"],
                        next_node_id,
                    )
                    current_node_id = next_node_id
                    continue
                current_node_id = next_node_id

            state_machine.transition(TransitionEvent.COMPLETE)
            self.logger.info(
                "scheduler.run.completed sessionId=%s executionId=%s totalCost=%.6f inputTokens=%s outputTokens=%s modelsUsed=%s planRound=%s",
                context.session_id,
                context.execution_id,
                total_cost,
                total_input_tokens,
                total_output_tokens,
                sorted(models_used),
                context.plan_round,
            )
            runtime.emit("execution.completed", {
                "execution_id": context.execution_id,
                "session_id": context.session_id,
                "status": "completed",
                "ended_at": utc_now_iso(),
                "output": dict(context.execution_variables),
                "variables": dict(context.execution_variables),
                "metrics": {
                    "trace_id": context.trace_id,
                    "total_cost": round(total_cost, 6),
                    "input_tokens": total_input_tokens,
                    "output_tokens": total_output_tokens,
                    "models_used": sorted(models_used),
                    "route_decision": context.route_decision,
                    "route_confidence": context.route_confidence,
                    "experiment_id": context.experiment_id,
                    "experiment_group": context.experiment_group,
                    "dynamic_threshold": context.dynamic_threshold,
                    "threshold_source": context.threshold_source,
                    "plan_round": context.plan_round,
                },
                "trace_id": context.trace_id,
            })
            runtime.emit("replay.snapshot_ready", {
                "execution_id": context.execution_id,
                "session_id": context.session_id,
                "workflow_code": context.workflow_code,
                "workflow_version": context.workflow_version,
                "snapshot": runtime.snapshot(),
                "trace_id": context.trace_id,
            })
        except InvalidOutputError as exc:
            self.logger.exception(
                "scheduler.run.output_rejected sessionId=%s executionId=%s nodeId=%s message=%s",
                context.session_id,
                context.execution_id,
                context.current_node_id,
                exc,
            )
            if context.current_node_id:
                context.record_failed_node(context.current_node_id, str(exc))
            runtime.emit("security.output_rejected", {
                "execution_id": context.execution_id,
                "session_id": context.session_id,
                "node_id": context.current_node_id,
                "error": str(exc),
                "details": exc.details,
                "trace_id": context.trace_id,
            })
            state_machine.transition(TransitionEvent.FAIL)
            runtime.emit("execution.failed", {
                "execution_id": context.execution_id,
                "session_id": context.session_id,
                "error": str(exc),
                "trace_id": context.trace_id,
            })
        except ConfirmationRequiredError as exc:
            self.logger.exception(
                "scheduler.run.confirmation_required sessionId=%s executionId=%s nodeId=%s toolCode=%s",
                context.session_id,
                context.execution_id,
                context.current_node_id,
                exc.tool_code,
            )
            if context.current_node_id:
                context.record_failed_node(context.current_node_id, str(exc))
            runtime.emit("confirmation.required", {
                "execution_id": context.execution_id,
                "session_id": context.session_id,
                "node_id": context.current_node_id,
                **exc.payload,
                "trace_id": context.trace_id,
            })
            state_machine.transition(TransitionEvent.FAIL)
            runtime.emit("execution.failed", {
                "execution_id": context.execution_id,
                "session_id": context.session_id,
                "error": str(exc),
                "trace_id": context.trace_id,
            })
        except Exception as exc:
            self.logger.exception(
                "scheduler.run.failed sessionId=%s executionId=%s workflowCode=%s workflowVersion=%s nodeId=%s",
                context.session_id,
                context.execution_id,
                context.workflow_code,
                context.workflow_version,
                context.current_node_id,
            )
            if context.current_node_id:
                context.record_failed_node(context.current_node_id, str(exc))
            state_machine.transition(TransitionEvent.FAIL)
            if context.current_node_id:
                node_type = self._resolve_node_type(workflow, context.current_node_id, context.current_graph_id)
            else:
                node_type = None
            if node_type:
                workflow_telemetry.record_node(
                    context.workflow_code,
                    node_type,
                    "failed",
                    0,
                )
            runtime.emit("execution.failed", {
                "execution_id": context.execution_id,
                "session_id": context.session_id,
                "error": str(exc),
                "trace_id": context.trace_id,
            })
        finally:
            runtime.mark_done()

    async def _suspend_execution(
        self,
        runtime: ExecutionRuntime,
        state_machine: ExecutionStateMachine,
        reason: str,
    ) -> None:
        context = runtime.context
        if state_machine.can_transition(TransitionEvent.SUSPEND):
            state_machine.transition(TransitionEvent.SUSPEND)
        runtime.emit("execution.suspended", {
            "execution_id": context.execution_id,
            "session_id": context.session_id,
            "workflow_code": context.workflow_code,
            "workflow_version": context.workflow_version,
            "reason": reason,
            "status": context.status,
            "snapshot": runtime.snapshot(),
            "trace_id": context.trace_id,
        })
        runtime.prepare_wait()
        await runtime.wait_for_resume()
        if state_machine.can_transition(TransitionEvent.RESUME):
            state_machine.transition(TransitionEvent.RESUME)
        runtime.emit("execution.resumed", {
            "execution_id": context.execution_id,
            "session_id": context.session_id,
            "status": context.status,
            "resume_type": "manual_resume",
            "trace_id": context.trace_id,
        })

    async def _run_graph_runtime_v2(self, runtime: ExecutionRuntime) -> Dict[str, Any]:
        context = runtime.context
        workflow = runtime.workflow
        total_cost = 0.0
        total_input_tokens = 0
        total_output_tokens = 0
        models_used: set[str] = set()
        main_graph_id = str(workflow.get("main_graph_id", "")).strip()
        if not main_graph_id:
            raise ValueError("workflow_definition v2 requires main_graph_id")

        main_graph = self._get_graph(workflow, main_graph_id)
        if not main_graph:
            raise ValueError(f"Main graph not found: {main_graph_id}")

        self.logger.info(
            "scheduler.graph_v2.start sessionId=%s executionId=%s mainGraphId=%s graphCount=%s",
            context.session_id,
            context.execution_id,
            main_graph_id,
            len(workflow.get("graphs", {}) or {}),
        )
        context.enter_graph(main_graph_id)
        runtime.emit("graph.entered", {
            "execution_id": context.execution_id,
            "session_id": context.session_id,
            "graph_id": main_graph_id,
            "parent_graph_id": None,
            "parent_node_id": None,
            "trace_id": context.trace_id,
        })

        current_graph_id = main_graph_id
        current_node_id = main_graph.get("entry_node_id")
        if not current_node_id:
            raise ValueError(f"Graph {main_graph_id} missing entry_node_id")

        while True:
            suspend_reason = runtime.consume_suspend_request()
            if suspend_reason:
                self.logger.warning(
                    "scheduler.graph_v2.suspend_unsupported sessionId=%s executionId=%s graphId=%s nodeId=%s reason=%s",
                    context.session_id,
                    context.execution_id,
                    current_graph_id,
                    current_node_id,
                    suspend_reason,
                )
                raise ValueError("Suspension is not supported for workflow_definition v2 baseline")

            graph = self._get_graph(workflow, current_graph_id)
            if not graph:
                self.logger.warning("scheduler.graph_v2.graph_missing sessionId=%s executionId=%s graphId=%s", context.session_id, context.execution_id, current_graph_id)
                raise ValueError(f"Graph not found: {current_graph_id}")
            node_def = graph.get("nodes", {}).get(current_node_id)
            if not node_def:
                self.logger.warning("scheduler.graph_v2.node_missing sessionId=%s executionId=%s graphId=%s nodeId=%s", context.session_id, context.execution_id, current_graph_id, current_node_id)
                raise ValueError(f"Node not found: {current_graph_id}.{current_node_id}")

            context.update_node_position(current_node_id)
            targets = self._collect_graph_targets(graph, current_node_id)
            context.set_available_targets(targets)
            self.logger.info(
                "scheduler.graph_v2.node.prepare sessionId=%s executionId=%s graphId=%s nodeId=%s nodeType=%s targets=%s",
                context.session_id,
                context.execution_id,
                current_graph_id,
                current_node_id,
                node_def["type"],
                targets,
            )
            runtime.emit("branch.candidates_prepared", {
                "execution_id": context.execution_id,
                "session_id": context.session_id,
                "graph_id": current_graph_id,
                "node_id": current_node_id,
                "node_type": node_def["type"],
                "candidates": list(targets),
                "trace_id": context.trace_id,
            })

            node = self._build_node(node_def)
            self.logger.info(
                "scheduler.graph_v2.node.start sessionId=%s executionId=%s graphId=%s nodeId=%s nodeType=%s",
                context.session_id,
                context.execution_id,
                current_graph_id,
                node_def["id"],
                node_def["type"],
            )
            runtime.emit("node.started", {
                "execution_id": context.execution_id,
                "session_id": context.session_id,
                "graph_id": current_graph_id,
                "node_id": node_def["id"],
                "node_type": node_def["type"],
                "started_at": utc_now_iso(),
                "trace_id": context.trace_id,
            })

            context.record_node_input_snapshot(node_def["id"], dict(context.execution_variables))
            started_at = time.perf_counter()
            with workflow_telemetry.span("execute_node", {
                "node.id": node_def["id"],
                "node.type": node_def["type"],
                "execution.id": context.execution_id,
                "workflow.code": context.workflow_code,
                "graph.id": current_graph_id,
            }):
                result = await node.execute(context)
            duration_ms = int((time.perf_counter() - started_at) * 1000)
            self.logger.info(
                "scheduler.graph_v2.node.result sessionId=%s executionId=%s graphId=%s nodeId=%s nodeType=%s durationMs=%s resultKeys=%s outputKeys=%s",
                context.session_id,
                context.execution_id,
                current_graph_id,
                node_def["id"],
                node_def["type"],
                duration_ms,
                sorted(result.keys()) if isinstance(result, dict) else [],
                sorted(result.get("output", {}).keys()) if isinstance(result.get("output"), dict) else [],
            )

            for security_event in result.get("security_events", []):
                runtime.emit(security_event["event_type"], {
                    "execution_id": context.execution_id,
                    "session_id": context.session_id,
                    "graph_id": current_graph_id,
                    "node_id": node_def["id"],
                    **security_event.get("data", {}),
                    "trace_id": context.trace_id,
                })
            for protection_event in result.get("protection_events", []):
                runtime.emit(protection_event["event_type"], {
                    "execution_id": context.execution_id,
                    "session_id": context.session_id,
                    "graph_id": current_graph_id,
                    "node_id": node_def["id"],
                    **protection_event.get("data", {}),
                    "trace_id": context.trace_id,
                })
            if result.get("tool_called"):
                called = result["tool_called"]
                runtime.emit("tool.called", {
                    "execution_id": context.execution_id,
                    "session_id": context.session_id,
                    "graph_id": current_graph_id,
                    "node_id": node_def["id"],
                    "tool_code": called.get("tool_code"),
                    "params": called.get("params", {}),
                    "group_code": called.get("group_code"),
                    "group_snapshot_version": called.get("group_snapshot_version"),
                    "capability_code": called.get("capability_code"),
                    "capability_version": called.get("capability_version"),
                    "capability_type": called.get("capability_type"),
                    "trace_id": context.trace_id,
                })
            if result.get("tool_returned"):
                returned = result["tool_returned"]
                runtime.emit("tool.returned", {
                    "execution_id": context.execution_id,
                    "session_id": context.session_id,
                    "graph_id": current_graph_id,
                    "node_id": node_def["id"],
                    "tool_code": returned.get("tool_code"),
                    "params": returned.get("params", {}),
                    "output": returned.get("output", {}),
                    "error": returned.get("error"),
                    "group_code": returned.get("group_code"),
                    "group_snapshot_version": returned.get("group_snapshot_version"),
                    "capability_code": returned.get("capability_code"),
                    "capability_version": returned.get("capability_version"),
                    "capability_type": returned.get("capability_type"),
                    "status": "completed",
                    "trace_id": context.trace_id,
                })
            for delta in result.get("message_deltas", []):
                runtime.emit("message.delta", {
                    "execution_id": context.execution_id,
                    "session_id": context.session_id,
                    "graph_id": current_graph_id,
                    "content": delta,
                    "delta_type": "text",
                    "trace_id": context.trace_id,
                })

            output = result.get("output", {})
            output_snapshot = output if isinstance(output, dict) else {"value": output}
            context.record_node_output_snapshot(node_def["id"], output_snapshot)
            context.record_completed_node(node_def["id"], output if isinstance(output, dict) else {})
            self.workflow_journal.record_execution(
                node_def["id"],
                "completed",
                output if isinstance(output, dict) else {},
            )
            metrics = dict(result.get("metrics", {}))
            metrics.setdefault("duration_ms", duration_ms)
            metrics.setdefault("trace_id", context.trace_id)
            if "cost" in metrics:
                total_cost += float(metrics.get("cost", 0.0))
                total_input_tokens += int(metrics.get("input_tokens", 0))
                total_output_tokens += int(metrics.get("output_tokens", 0))
                if metrics.get("model"):
                    model_name = str(metrics["model"])
                    models_used.add(model_name)
                    workflow_telemetry.record_llm_cost(
                        model_name,
                        context.workflow_code,
                        int(metrics.get("input_tokens", 0)),
                        int(metrics.get("output_tokens", 0)),
                        float(metrics.get("cost", 0.0)),
                    )
                runtime.emit("cost.recorded", {
                    "execution_id": context.execution_id,
                    "session_id": context.session_id,
                    "workflow_code": context.workflow_code,
                    "workflow_version": context.workflow_version,
                    "experiment_id": context.experiment_id,
                    "experiment_group": context.experiment_group,
                    **metrics,
                    "trace_id": context.trace_id,
                })
                for alert in budget_alert_evaluator.evaluate(context.workflow_code, context.user_id, total_cost):
                    runtime.emit("budget.alert", {
                        "execution_id": context.execution_id,
                        "session_id": context.session_id,
                        "scope": alert.scope,
                        "scope_id": alert.scope_id,
                        "total_cost": alert.total_cost,
                        "threshold": alert.threshold,
                        "message": alert.message,
                        "trace_id": context.trace_id,
                    })
            runtime.emit("node.completed", {
                "execution_id": context.execution_id,
                "session_id": context.session_id,
                "graph_id": current_graph_id,
                "node_id": node_def["id"],
                "node_type": node_def["type"],
                "status": "completed",
                "output": output,
                "metrics": metrics,
                "trace_id": context.trace_id,
            })
            workflow_telemetry.record_node(context.workflow_code, node_def["type"], "completed", duration_ms)
            self.logger.info(
                "scheduler.graph_v2.node.completed sessionId=%s executionId=%s graphId=%s nodeId=%s nodeType=%s durationMs=%s totalCost=%.6f",
                context.session_id,
                context.execution_id,
                current_graph_id,
                node_def["id"],
                node_def["type"],
                duration_ms,
                total_cost,
            )

            if node_def["type"] == "function":
                runtime.emit("function.executed", {
                    "execution_id": context.execution_id,
                    "session_id": context.session_id,
                    "graph_id": current_graph_id,
                    "node_id": node_def["id"],
                    "operation_type": result.get("operation_type", "assign"),
                    "output": output if isinstance(output, dict) else {},
                    "trace_id": context.trace_id,
                })

            enter_subgraph = result.get("enter_subgraph")
            if enter_subgraph:
                subgraph_id = str(enter_subgraph.get("graph_id", "")).strip()
                self.logger.info(
                    "scheduler.graph_v2.subgraph.enter_prepare sessionId=%s executionId=%s fromGraphId=%s fromNodeId=%s targetGraphId=%s",
                    context.session_id,
                    context.execution_id,
                    current_graph_id,
                    node_def["id"],
                    subgraph_id,
                )
                if not subgraph_id:
                    raise ValueError(f"sub_agent node {node_def['id']} missing subgraph_id")
                subgraph = self._get_graph(workflow, subgraph_id)
                if not subgraph:
                    raise ValueError(f"Subgraph not found: {subgraph_id}")
                subgraph_entry = subgraph.get("entry_node_id")
                if not subgraph_entry:
                    raise ValueError(f"Subgraph {subgraph_id} missing entry_node_id")
                input_variables = enter_subgraph.get("input_variables", {}) or {}
                if not isinstance(input_variables, dict):
                    raise ValueError("sub_agent input_variables must be an object")
                if input_variables:
                    context.add_execution_variables(input_variables)
                context.enter_graph(
                    subgraph_id,
                    parent_graph_id=current_graph_id,
                    parent_node_id=node_def["id"],
                    output_mapping=enter_subgraph.get("output_mapping", {}) or {},
                )
                runtime.emit("graph.entered", {
                    "execution_id": context.execution_id,
                    "session_id": context.session_id,
                    "graph_id": subgraph_id,
                    "parent_graph_id": current_graph_id,
                    "parent_node_id": node_def["id"],
                    "trace_id": context.trace_id,
                })
                current_graph_id = subgraph_id
                current_node_id = subgraph_entry
                continue

            next_node_id = await self._next_node_v2(graph, node_def, result, context)
            self.logger.info(
                "scheduler.graph_v2.branch.resolved sessionId=%s executionId=%s graphId=%s nodeId=%s nextNode=%s targetCount=%s",
                context.session_id,
                context.execution_id,
                current_graph_id,
                node_def["id"],
                next_node_id,
                len(targets),
            )
            if len(targets) > 1 or node_def["type"] in {"coordinator", "sub_agent"}:
                decision_payload = self._react_decision_payload(result)
                runtime.emit("branch.decided", {
                    "execution_id": context.execution_id,
                    "session_id": context.session_id,
                    "graph_id": current_graph_id,
                    "node_id": node_def["id"],
                    "node_type": node_def["type"],
                    "candidates": list(targets),
                    "action": decision_payload.get("action"),
                    "missing_fields": decision_payload.get("missing_fields", []),
                    "reason": decision_payload.get("reason_summary"),
                    "confidence": decision_payload.get("confidence"),
                    "targetNodeId": next_node_id,
                    "trace_id": context.trace_id,
                })

            if node_def["type"] != "end":
                if next_node_id is None:
                    self.logger.warning(
                        "scheduler.graph_v2.next_missing sessionId=%s executionId=%s graphId=%s nodeId=%s",
                        context.session_id,
                        context.execution_id,
                        current_graph_id,
                        node_def["id"],
                    )
                    raise ValueError(f"Node {current_graph_id}.{node_def['id']} has no next node")
                current_node_id = next_node_id
                continue

            exited_graph_id = current_graph_id
            frame = context.exit_graph()
            if not frame:
                raise ValueError(f"Graph stack underflow while exiting {exited_graph_id}")
            runtime.emit("graph.exited", {
                "execution_id": context.execution_id,
                "session_id": context.session_id,
                "graph_id": exited_graph_id,
                "parent_graph_id": frame.parent_graph_id,
                "parent_node_id": frame.parent_node_id,
                "trace_id": context.trace_id,
            })
            self.logger.info(
                "scheduler.graph_v2.graph.exited sessionId=%s executionId=%s graphId=%s parentGraphId=%s parentNodeId=%s",
                context.session_id,
                context.execution_id,
                exited_graph_id,
                frame.parent_graph_id,
                frame.parent_node_id,
            )

            if not frame.parent_graph_id:
                self.logger.info(
                    "scheduler.graph_v2.completed sessionId=%s executionId=%s totalCost=%.6f inputTokens=%s outputTokens=%s modelsUsed=%s",
                    context.session_id,
                    context.execution_id,
                    total_cost,
                    total_input_tokens,
                    total_output_tokens,
                    sorted(models_used),
                )
                return {
                    "total_cost": round(total_cost, 6),
                    "input_tokens": total_input_tokens,
                    "output_tokens": total_output_tokens,
                    "models_used": sorted(models_used),
                }

            subflow_output = output if isinstance(output, dict) else {}
            if frame.output_mapping:
                self._apply_output_mapping(frame.output_mapping, subflow_output, context)
            else:
                for key, value in subflow_output.items():
                    context.add_execution_variable(key, value)

            parent_graph = self._get_graph(workflow, frame.parent_graph_id)
            if not parent_graph:
                raise ValueError(f"Parent graph not found: {frame.parent_graph_id}")
            if not frame.parent_node_id:
                raise ValueError(f"Parent node id missing when exiting graph {exited_graph_id}")
            parent_node = parent_graph.get("nodes", {}).get(frame.parent_node_id)
            if not parent_node:
                raise ValueError(f"Parent node not found: {frame.parent_graph_id}.{frame.parent_node_id}")

            current_graph_id = frame.parent_graph_id
            current_node_id = await self._next_node_v2(
                parent_graph,
                parent_node,
                {"output": subflow_output},
                context,
            )
            self.logger.info(
                "scheduler.graph_v2.parent.branch_resolved sessionId=%s executionId=%s graphId=%s parentNodeId=%s nextNode=%s",
                context.session_id,
                context.execution_id,
                current_graph_id,
                frame.parent_node_id,
                current_node_id,
            )
            parent_targets = self._collect_graph_targets(parent_graph, frame.parent_node_id)
            decision_payload = self._react_decision_payload({"output": subflow_output})
            runtime.emit("branch.decided", {
                "execution_id": context.execution_id,
                "session_id": context.session_id,
                "graph_id": current_graph_id,
                "node_id": frame.parent_node_id,
                "node_type": parent_node.get("type"),
                "candidates": list(parent_targets),
                "action": decision_payload.get("action"),
                "missing_fields": decision_payload.get("missing_fields", []),
                "reason": decision_payload.get("reason_summary"),
                "confidence": decision_payload.get("confidence"),
                "targetNodeId": current_node_id,
                "trace_id": context.trace_id,
            })
            if current_node_id is None:
                if current_graph_id == str(workflow.get("main_graph_id", "")).strip():
                    return {
                        "total_cost": round(total_cost, 6),
                        "input_tokens": total_input_tokens,
                        "output_tokens": total_output_tokens,
                        "models_used": sorted(models_used),
                    }
                raise ValueError(f"Node {current_graph_id}.{frame.parent_node_id} has no next node")

    def _is_graph_definition_v2(self, workflow: Dict[str, Any]) -> bool:
        return isinstance(workflow, dict) and isinstance(workflow.get("graphs"), dict) and bool(workflow.get("main_graph_id"))

    def _get_graph(self, workflow: Dict[str, Any], graph_id: str) -> Optional[Dict[str, Any]]:
        graphs = workflow.get("graphs")
        if not isinstance(graphs, dict):
            return None
        graph = graphs.get(graph_id)
        return graph if isinstance(graph, dict) else None

    def _collect_graph_targets(self, graph: Dict[str, Any], node_id: str) -> List[str]:
        edges = graph.get("edges", [])
        targets: List[str] = []
        if not isinstance(edges, list):
            return targets
        for edge in edges:
            if not isinstance(edge, dict):
                continue
            if edge.get("source") != node_id:
                continue
            target = edge.get("target")
            if isinstance(target, str) and target:
                targets.append(target)
        return targets

    async def _next_node_v2(
        self,
        graph: Dict[str, Any],
        node_def: Dict[str, Any],
        result: Dict[str, Any],
        context,
    ) -> Optional[str]:
        targets = self._collect_graph_targets(graph, node_def["id"])
        explicit_target = self._extract_target_node_id(result)

        if explicit_target and explicit_target not in targets:
            raise ValueError(
                f"Invalid targetNodeId '{explicit_target}' for node {node_def['id']}; allowed: {targets}"
            )

        if not targets:
            return None
        if explicit_target:
            return explicit_target
        if len(targets) == 1:
            return targets[0]

        branch_target = self._resolve_graph_branch_target(graph, node_def["id"], result)
        if branch_target:
            return branch_target

        candidates = self._graph_transition_candidates(graph, node_def["id"], targets)
        decision = await self.react_decision_service.decide_next_node(
            current_node=node_def,
            result=result,
            candidates=candidates,
            context=context,
        )
        self._attach_react_decision(result, decision)
        return decision.target_node_id

    def _resolve_graph_branch_target(
            self,
            graph: Dict[str, Any],
            source_node_id: str,
            result: Dict[str, Any]
    ) -> Optional[str]:
        branch = result.get("branch")
        if not isinstance(branch, str) or not branch:
            return None
        for edge in graph.get("edges", []):
            if not isinstance(edge, dict):
                continue
            if edge.get("source") != source_node_id:
                continue
            edge_branch = edge.get("branch") or edge.get("label") or edge.get("condition")
            if edge_branch == branch and isinstance(edge.get("target"), str) and edge.get("target"):
                return str(edge["target"])
        return None

    def _extract_target_node_id(self, result: Dict[str, Any]) -> Optional[str]:
        for key in ("next_node", "targetNodeId"):
            value = result.get(key)
            if isinstance(value, str) and value:
                return value
        output = result.get("output")
        if isinstance(output, dict):
            value = output.get("targetNodeId")
            if isinstance(value, str) and value:
                return value
        return None

    def _apply_output_mapping(self, mapping: Dict[str, Any], source: Dict[str, Any], context) -> None:
        for target, reference in mapping.items():
            value = self._resolve_mapping_reference(str(reference), source, context)
            self._assign_mapping_target(str(target), value, context)

    def _resolve_mapping_reference(self, reference: str, source: Dict[str, Any], context) -> Any:
        if reference.startswith("$execution."):
            return self._read_nested(context.execution_variables, reference[len("$execution."):])
        if reference.startswith("$session."):
            return self._read_nested(context.session_variables, reference[len("$session."):])
        if reference.startswith("$subflow.output."):
            return self._read_nested(source, reference[len("$subflow.output."):])
        if reference.startswith("$node.output."):
            return self._read_nested(source, reference[len("$node.output."):])
        return None

    def _assign_mapping_target(self, target: str, value: Any, context) -> None:
        if target.startswith("$execution."):
            context.add_execution_variable(target[len("$execution."):], value)
            return
        if target.startswith("$session."):
            context.add_session_variable(target[len("$session."):], value)

    def _read_nested(self, source: Any, path: str) -> Any:
        current = source
        for segment in path.split("."):
            if not segment:
                continue
            if isinstance(current, dict):
                current = current.get(segment)
                continue
            if isinstance(current, list) and segment.isdigit():
                index = int(segment)
                current = current[index] if 0 <= index < len(current) else None
                continue
            return None
        return current

    def _resolve_node_type(
        self,
        workflow: Dict[str, Any],
        node_id: Optional[str],
        graph_id: Optional[str] = None,
    ) -> Optional[str]:
        if not node_id:
            return None
        if self._is_graph_definition_v2(workflow):
            if graph_id:
                graph = self._get_graph(workflow, graph_id)
                if graph:
                    node = graph.get("nodes", {}).get(node_id)
                    if isinstance(node, dict):
                        node_type = node.get("type")
                        return str(node_type) if node_type else None
            graphs = workflow.get("graphs", {})
            for graph in graphs.values():
                if not isinstance(graph, dict):
                    continue
                node = graph.get("nodes", {}).get(node_id)
                if isinstance(node, dict):
                    node_type = node.get("type")
                    return str(node_type) if node_type else None
            return None
        node = workflow.get("nodes", {}).get(node_id)
        if isinstance(node, dict):
            node_type = node.get("type")
            return str(node_type) if node_type else None
        return None

    def _build_node(self, node_def: Dict[str, Any]):
        node_type = node_def["type"]
        if node_type == "start":
            return StartNode(node_def["id"], node_def)
        if node_type == "end":
            return EndNode(node_def["id"], node_def)
        if node_type == "coordinator":
            return CoordinatorNode(node_def["id"], node_def)
        if node_type == "sub_agent":
            config = node_def.get("config", {})
            if config.get("subgraph_id") or config.get("subflow_code"):
                return SubflowNode(node_def["id"], node_def)
            return LLMNode(node_def["id"], node_def)
        if node_type == "function":
            return FunctionNode(node_def["id"], node_def)
        if node_type in {"llm", "coordinate", "planner"}:
            return LLMNode(node_def["id"], node_def)
        if node_type == "condition":
            return ConditionNode(node_def["id"], node_def)
        if node_type == "form":
            return FormNode(node_def["id"], node_def)
        if node_type == "message":
            return MessageNode(node_def["id"], node_def)
        if node_type == "knowledge":
            return KnowledgeNode(node_def["id"], node_def)
        if node_type == "subflow":
            return SubflowNode(node_def["id"], node_def)
        return ToolNode(node_def["id"], node_def)

    async def _next_node(self, workflow: Dict[str, Any], node_def: Dict[str, Any], result: Dict[str, Any], context) -> Optional[str]:
        transitions = workflow.get("transitions", {})
        node_id = node_def["id"]
        node_type = node_def["type"]

        explicit_next = result.get("next_node")
        if explicit_next:
            return explicit_next

        if node_type == "condition":
            branch = result.get("branch")
            return transitions.get(node_id, {}).get(branch)

        transition = transitions.get(node_id)
        if isinstance(transition, dict):
            candidates = self._legacy_transition_candidates(workflow, node_id, transition)
            decision = await self.react_decision_service.decide_next_node(
                current_node=node_def,
                result=result,
                candidates=candidates,
                context=context,
            )
            self._attach_react_decision(result, decision)
            return decision.target_node_id

        return transition

    def _graph_transition_candidates(
        self,
        graph: Dict[str, Any],
        source_node_id: str,
        targets: List[str],
    ) -> List[Dict[str, Any]]:
        nodes = graph.get("nodes", {})
        candidates: List[Dict[str, Any]] = []
        for target in targets:
            edge = self._find_graph_edge(graph, source_node_id, target)
            candidates.append({
                "target_node_id": target,
                "branch": edge.get("branch") or edge.get("label") or edge.get("condition"),
                "description": edge.get("description") or edge.get("label") or "",
                "node": nodes.get(target, {"id": target, "type": "unknown", "config": {}}),
            })
        return candidates

    def _legacy_transition_candidates(
        self,
        workflow: Dict[str, Any],
        source_node_id: str,
        transition: Any,
    ) -> List[Dict[str, Any]]:
        nodes = workflow.get("nodes", {})
        if isinstance(transition, dict):
            return [
                {
                    "target_node_id": target,
                    "branch": branch,
                    "description": str(branch),
                    "node": nodes.get(target, {"id": target, "type": "unknown", "config": {}}),
                }
                for branch, target in transition.items()
                if isinstance(target, str) and target
            ]
        if isinstance(transition, str) and transition:
            return [{
                "target_node_id": transition,
                "branch": None,
                "description": "",
                "node": nodes.get(transition, {"id": transition, "type": "unknown", "config": {}}),
            }]
        return []

    def _find_graph_edge(self, graph: Dict[str, Any], source_node_id: str, target_node_id: str) -> Dict[str, Any]:
        for edge in graph.get("edges", []):
            if edge.get("source") == source_node_id and edge.get("target") == target_node_id:
                return edge
        return {}

    def _attach_react_decision(self, result: Dict[str, Any], decision) -> None:
        result["react_decision"] = {
            "targetNodeId": decision.target_node_id,
            "action": decision.action,
            "missing_fields": list(decision.missing_fields),
            "confidence": decision.confidence,
            "reason_summary": decision.reason_summary,
        }

    def _react_decision_payload(self, result: Dict[str, Any]) -> Dict[str, Any]:
        payload = result.get("react_decision")
        return payload if isinstance(payload, dict) else {}
