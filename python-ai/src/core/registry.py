import asyncio
import logging
from copy import deepcopy
from typing import Any, Dict, Optional

from .context import ExecutionContext
from .protection import runtime_protection_manager
from .runtime import ExecutionRuntime


logger = logging.getLogger(__name__)


class ExecutionRegistry:
    def __init__(self):
        self._executions: Dict[str, ExecutionRuntime] = {}
        self._message_index: Dict[tuple[str, str], str] = {}
        self._lock = asyncio.Lock()
        self.max_concurrent_executions_per_session = 2

    async def create_execution(self, payload: Dict[str, Any]) -> ExecutionRuntime:
        async with self._lock:
            execution_id = payload["execution_id"]
            message_id = payload.get("message_id")
            session_id = payload["session_id"]
            logger.info(
                "registry.create.request sessionId=%s executionId=%s messageId=%s workflowCode=%s workflowVersion=%s routeDecision=%s",
                session_id,
                execution_id,
                message_id,
                payload.get("workflow_code"),
                payload.get("workflow_version"),
                payload.get("route_decision"),
            )
            if message_id:
                existing_execution_id = self._message_index.get((session_id, message_id))
                if existing_execution_id:
                    existing_runtime = self._executions.get(existing_execution_id)
                    if existing_runtime:
                        logger.info(
                            "registry.create.idempotency_hit sessionId=%s messageId=%s executionId=%s status=%s",
                            session_id,
                            message_id,
                            existing_execution_id,
                            existing_runtime.context.status,
                        )
                        return existing_runtime
            if execution_id in self._executions:
                existing_runtime = self._executions.get(execution_id)
                if existing_runtime:
                    logger.info(
                        "registry.create.execution_hit sessionId=%s executionId=%s status=%s",
                        session_id,
                        execution_id,
                        existing_runtime.context.status,
                    )
                    return existing_runtime
                logger.warning("registry.create.duplicate_missing_runtime executionId=%s", execution_id)
                raise ValueError(f"Execution already exists: {execution_id}")

            active_count = sum(
                1
                for runtime in self._executions.values()
                if runtime.context.session_id == session_id
                and runtime.context.status not in {"completed", "failed", "cancelled", "suspended"}
            )
            logger.info(
                "registry.create.concurrency_checked sessionId=%s activeCount=%s limit=%s",
                session_id,
                active_count,
                self.max_concurrent_executions_per_session,
            )
            if active_count >= self.max_concurrent_executions_per_session:
                logger.warning(
                    "registry.create.concurrency_rejected sessionId=%s activeCount=%s limit=%s",
                    session_id,
                    active_count,
                    self.max_concurrent_executions_per_session,
                )
                raise ValueError(f"Session concurrency limit reached: {session_id}")

            workflow_code = payload["workflow_code"]
            workflow_version = payload["workflow_version"]
            workflow = payload.get("workflow_definition") or {}
            if not workflow:
                catalog = payload.get("workflow_catalog", {}) or {}
                workflow = catalog.get(f"{workflow_code}@{workflow_version}") or {}
                if workflow:
                    logger.info("registry.create.workflow_from_catalog executionId=%s workflowCode=%s workflowVersion=%s", execution_id, workflow_code, workflow_version)
            if not workflow:
                logger.warning("registry.create.workflow_missing executionId=%s workflowCode=%s workflowVersion=%s", execution_id, workflow_code, workflow_version)
                raise ValueError(f"Workflow definition missing: {workflow_code}@{workflow_version}")
            workflow = self._normalize_workflow_definition(workflow)
            logger.info(
                "registry.create.workflow_ready executionId=%s graphV2=%s nodeCount=%s providerCount=%s modelRecordCount=%s",
                execution_id,
                isinstance(workflow.get("graphs"), dict) if isinstance(workflow, dict) else False,
                self._count_nodes(workflow),
                len(payload.get("provider_configs", []) or []),
                len(payload.get("model_records", []) or []),
            )

            provider_configs = {
                str(item.get("provider_code")): item
                for item in payload.get("provider_configs", [])
                if item.get("provider_code")
            }
            model_records = {
                str(item.get("model_code")): item
                for item in payload.get("model_records", [])
                if item.get("model_code")
            }

            workflow_config = dict(payload.get("workflow_config", {}) or {})
            system_prompts = dict(payload.get("system_prompts", {}) or {})
            if system_prompts:
                merged_system_prompts = dict(workflow_config.get("system_prompts", {}) or {})
                merged_system_prompts.update(system_prompts)
                workflow_config["system_prompts"] = merged_system_prompts

            context = ExecutionContext(
                execution_id=payload["execution_id"],
                session_id=payload["session_id"],
                workflow_code=workflow_code,
                workflow_version=workflow_version,
                message_id=message_id,
                priority=int(payload.get("priority", 0)),
                route_decision=payload.get("route_decision", "start"),
                route_reason=payload.get("route_reason"),
                route_confidence=float(payload.get("route_confidence", 0.0)),
                user_id=payload.get("user_id", "anonymous"),
                experiment_id=payload.get("experiment_id"),
                experiment_group=payload.get("experiment_group"),
                dynamic_threshold=payload.get("dynamic_threshold"),
                threshold_source=payload.get("threshold_source"),
                requested_tool_code=payload.get("requested_tool_code"),
                confirmed_tool_codes=list(payload.get("confirmed_tool_codes", []) or []),
                workflow_config=workflow_config,
                workflow_catalog=dict(payload.get("workflow_catalog", {}) or {}),
                provider_configs=provider_configs,
                model_records=model_records,
                routing_model_code=payload.get("routing_model_code"),
            )
            context.add_execution_variables(payload.get("input_variables", {}))
            if context.confirmed_tool_codes:
                context.add_execution_variable("confirmed_tool_codes", list(context.confirmed_tool_codes))
            logger.info(
                "registry.create.context_ready sessionId=%s executionId=%s userId=%s inputKeys=%s confirmedToolCount=%s routingModelCode=%s",
                context.session_id,
                context.execution_id,
                context.user_id,
                sorted((payload.get("input_variables", {}) or {}).keys()),
                len(context.confirmed_tool_codes),
                context.routing_model_code,
            )
            runtime_protection_manager.check_execution_start(context)
            logger.info("registry.create.protection_passed sessionId=%s executionId=%s", context.session_id, context.execution_id)

            runtime = ExecutionRuntime(context=context, workflow=workflow)
            self._executions[execution_id] = runtime
            if message_id:
                self._message_index[(session_id, message_id)] = execution_id
            logger.info(
                "registry.create.stored sessionId=%s executionId=%s messageId=%s totalExecutions=%s",
                session_id,
                execution_id,
                message_id,
                len(self._executions),
            )
            return runtime

    async def get_execution(self, execution_id: str) -> Optional[ExecutionRuntime]:
        async with self._lock:
            runtime = self._executions.get(execution_id)
            logger.info(
                "registry.get executionId=%s found=%s status=%s",
                execution_id,
                runtime is not None,
                runtime.context.status if runtime else None,
            )
            return runtime

    async def resume_execution(self, execution_id: str, form_data: Dict[str, Any]) -> ExecutionRuntime:
        logger.info("registry.resume.request executionId=%s formKeys=%s", execution_id, sorted((form_data or {}).keys()))
        runtime = await self.get_execution(execution_id)
        if not runtime:
            logger.warning("registry.resume.not_found executionId=%s", execution_id)
            raise ValueError(f"Execution not found: {execution_id}")
        runtime.resume(form_data)
        logger.info("registry.resume.signalled executionId=%s status=%s", execution_id, runtime.context.status)
        return runtime

    def _normalize_workflow_definition(self, workflow: Dict[str, Any]) -> Dict[str, Any]:
        if not isinstance(workflow, dict):
            return workflow
        if "graphs" not in workflow or "main_graph_id" not in workflow:
            return workflow

        normalized = deepcopy(workflow)
        graphs = normalized.get("graphs", {})
        if not isinstance(graphs, dict):
            return normalized

        for graph in graphs.values():
            if not isinstance(graph, dict):
                continue
            nodes = graph.get("nodes", {})
            if not isinstance(nodes, dict):
                continue
            for node in nodes.values():
                if not isinstance(node, dict):
                    continue
                node_type = str(node.get("type", "")).strip().lower()
                if node_type == "coordinate":
                    node["type"] = "coordinator"
                elif node_type == "subflow":
                    node["type"] = "sub_agent"

        return normalized

    def _count_nodes(self, workflow: Dict[str, Any]) -> int:
        if not isinstance(workflow, dict):
            return 0
        if isinstance(workflow.get("nodes"), dict):
            return len(workflow.get("nodes", {}))
        graphs = workflow.get("graphs")
        if not isinstance(graphs, dict):
            return 0
        total = 0
        for graph in graphs.values():
            if isinstance(graph, dict) and isinstance(graph.get("nodes"), dict):
                total += len(graph.get("nodes", {}))
        return total
