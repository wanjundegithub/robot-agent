import logging
import time
from typing import Any, Dict, Optional

from .costing import budget_alert_evaluator
from .events import utc_now_iso
from .protection import ConfirmationRequiredError
from .runtime import ExecutionRuntime
from .security import InvalidOutputError
from .state_machine import ExecutionStateMachine, TransitionEvent
from .telemetry import workflow_telemetry
from src.nodes import ConditionNode, EndNode, FormNode, KnowledgeNode, LLMNode, StartNode, SubflowNode, ToolNode


class WorkflowScheduler:
    """Phase 3 workflow scheduler with suspend/resume and security hooks."""

    def __init__(self):
        self.logger = logging.getLogger(__name__)

    async def run(self, runtime: ExecutionRuntime) -> None:
        context = runtime.context
        workflow = runtime.workflow
        state_machine = ExecutionStateMachine()
        state_machine.set_context(context)

        state_machine.transition(TransitionEvent.START)
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
            while current_node_id:
                suspend_reason = runtime.consume_suspend_request()
                if suspend_reason:
                    await self._suspend_execution(runtime, state_machine, suspend_reason)

                context.update_node_position(current_node_id)
                node_def = workflow["nodes"].get(current_node_id)
                if not node_def:
                    raise ValueError(f"Node not found: {current_node_id}")

                node = self._build_node(node_def)
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

                if node_def["type"] == "form":
                    runtime.last_form_definition = result.get("form_definition", {})
                    state_machine.transition(TransitionEvent.WAIT_USER)
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
                    runtime.emit("execution.resumed", {
                        "execution_id": context.execution_id,
                        "session_id": context.session_id,
                        "status": context.status,
                        "resume_type": "form_submit",
                        "trace_id": context.trace_id,
                    })
                else:
                    if result.get("tool_called"):
                        runtime.emit("tool.called", {
                            "execution_id": context.execution_id,
                            "session_id": context.session_id,
                            "node_id": node_def["id"],
                            "tool_code": result["tool_called"].get("tool_code"),
                            "params": result["tool_called"].get("params", {}),
                            "trace_id": context.trace_id,
                        })
                    if result.get("tool_returned"):
                        runtime.emit("tool.returned", {
                            "execution_id": context.execution_id,
                            "session_id": context.session_id,
                            "node_id": node_def["id"],
                            "tool_code": result["tool_returned"].get("tool_code"),
                            "output": result["tool_returned"].get("output", {}),
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
                runtime.emit("node.completed", {
                    "execution_id": context.execution_id,
                    "session_id": context.session_id,
                    "node_id": node_def["id"],
                    "node_type": node_def["type"],
                    "status": "completed",
                    "output": result.get("output", {}),
                    "metrics": metrics,
                    "trace_id": context.trace_id,
                })
                workflow_telemetry.record_node(context.workflow_code, node_def["type"], "completed", duration_ms)

                next_node_id = self._next_node(workflow, node_def, result)
                suspend_reason = runtime.consume_suspend_request()
                if suspend_reason:
                    await self._suspend_execution(runtime, state_machine, suspend_reason)
                current_node_id = next_node_id

            state_machine.transition(TransitionEvent.COMPLETE)
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
            self.logger.exception("Structured output validation failed")
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
            self.logger.exception("High-risk tool confirmation required")
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
            self.logger.exception("Execution failed")
            state_machine.transition(TransitionEvent.FAIL)
            if context.current_node_id:
                workflow_telemetry.record_node(
                    context.workflow_code,
                    workflow["nodes"][context.current_node_id]["type"],
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

    def _build_node(self, node_def: Dict[str, Any]):
        node_type = node_def["type"]
        if node_type == "start":
            return StartNode(node_def["id"], node_def)
        if node_type == "end":
            return EndNode(node_def["id"], node_def)
        if node_type == "llm":
            return LLMNode(node_def["id"], node_def)
        if node_type == "condition":
            return ConditionNode(node_def["id"], node_def)
        if node_type == "form":
            return FormNode(node_def["id"], node_def)
        if node_type == "knowledge":
            return KnowledgeNode(node_def["id"], node_def)
        if node_type == "subflow":
            return SubflowNode(node_def["id"], node_def)
        return ToolNode(node_def["id"], node_def)

    def _next_node(self, workflow: Dict[str, Any], node_def: Dict[str, Any], result: Dict[str, Any]) -> Optional[str]:
        transitions = workflow.get("transitions", {})
        node_id = node_def["id"]
        node_type = node_def["type"]

        if node_type == "condition":
            branch = result.get("branch")
            return transitions.get(node_id, {}).get(branch)

        return transitions.get(node_id)
