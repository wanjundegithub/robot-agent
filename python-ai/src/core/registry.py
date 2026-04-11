import asyncio
from typing import Any, Dict, Optional

from .context import ExecutionContext
from .protection import runtime_protection_manager
from .runtime import ExecutionRuntime
from .workflow_registry import get_workflow


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
            if message_id:
                existing_execution_id = self._message_index.get((session_id, message_id))
                if existing_execution_id:
                    existing_runtime = self._executions.get(existing_execution_id)
                    if existing_runtime:
                        return existing_runtime
            if execution_id in self._executions:
                raise ValueError(f"Execution already exists: {execution_id}")

            active_count = sum(
                1
                for runtime in self._executions.values()
                if runtime.context.session_id == session_id
                and runtime.context.status not in {"completed", "failed", "cancelled", "suspended"}
            )
            if active_count >= self.max_concurrent_executions_per_session:
                raise ValueError(f"Session concurrency limit reached: {session_id}")

            workflow_code = payload["workflow_code"]
            workflow_version = payload["workflow_version"]
            workflow = get_workflow(workflow_code, workflow_version)
            if not workflow:
                raise ValueError(f"Workflow not found: {workflow_code}@{workflow_version}")

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
            )
            context.add_execution_variables(payload.get("input_variables", {}))
            if context.confirmed_tool_codes:
                context.add_execution_variable("confirmed_tool_codes", list(context.confirmed_tool_codes))
            runtime_protection_manager.check_execution_start(context)

            runtime = ExecutionRuntime(context=context, workflow=workflow)
            self._executions[execution_id] = runtime
            if message_id:
                self._message_index[(session_id, message_id)] = execution_id
            return runtime

    async def get_execution(self, execution_id: str) -> Optional[ExecutionRuntime]:
        async with self._lock:
            return self._executions.get(execution_id)

    async def resume_execution(self, execution_id: str, form_data: Dict[str, Any]) -> ExecutionRuntime:
        runtime = await self.get_execution(execution_id)
        if not runtime:
            raise ValueError(f"Execution not found: {execution_id}")
        runtime.resume(form_data)
        return runtime
