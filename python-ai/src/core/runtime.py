import asyncio
import logging
from dataclasses import dataclass, field
from typing import Any, Dict, Optional, AsyncIterator

from .context import ExecutionContext
from .events import sse_format
from .security import mask_sensitive_fields


logger = logging.getLogger(__name__)


@dataclass
class ExecutionRuntime:
    context: ExecutionContext
    workflow: Dict[str, Any]
    queue: asyncio.Queue[str] = field(default_factory=asyncio.Queue)
    resume_event: asyncio.Event = field(default_factory=asyncio.Event)
    resume_data: Optional[Dict[str, Any]] = None
    last_form_response: Optional[Dict[str, Any]] = None
    last_form_definition: Optional[Dict[str, Any]] = None
    suspend_requested: bool = False
    suspend_reason: Optional[str] = None
    event_id: int = 0
    done: bool = False

    def next_event_id(self) -> int:
        self.event_id += 1
        return self.event_id

    def emit(self, event_type: str, data: Dict[str, Any]) -> None:
        event_id = self.next_event_id()
        safe_data = mask_sensitive_fields(data)
        logger.info(
            "runtime.emit queued sessionId=%s executionId=%s eventId=%s eventType=%s dataKeys=%s queueSize=%s",
            self.context.session_id,
            self.context.execution_id,
            event_id,
            event_type,
            sorted(safe_data.keys()),
            self.queue.qsize(),
        )
        self.queue.put_nowait(sse_format(event_id, event_type, safe_data))

    async def stream(self) -> AsyncIterator[str]:
        logger.info(
            "runtime.stream.start sessionId=%s executionId=%s",
            self.context.session_id,
            self.context.execution_id,
        )
        while True:
            if self.done and self.queue.empty():
                logger.info(
                    "runtime.stream.done sessionId=%s executionId=%s eventCount=%s",
                    self.context.session_id,
                    self.context.execution_id,
                    self.event_id,
                )
                break
            item = await self.queue.get()
            logger.info(
                "runtime.stream.yield sessionId=%s executionId=%s remainingQueueSize=%s",
                self.context.session_id,
                self.context.execution_id,
                self.queue.qsize(),
            )
            yield item

    def mark_done(self) -> None:
        self.done = True
        logger.info(
            "runtime.mark_done sessionId=%s executionId=%s status=%s eventCount=%s",
            self.context.session_id,
            self.context.execution_id,
            self.context.status,
            self.event_id,
        )

    def prepare_wait(self) -> None:
        logger.info(
            "runtime.wait.prepare sessionId=%s executionId=%s status=%s",
            self.context.session_id,
            self.context.execution_id,
            self.context.status,
        )
        self.resume_event.clear()
        self.resume_data = None

    async def wait_for_resume(self) -> Dict[str, Any]:
        logger.info(
            "runtime.wait.resume_start sessionId=%s executionId=%s",
            self.context.session_id,
            self.context.execution_id,
        )
        await self.resume_event.wait()
        data = self.resume_data or {}
        self.resume_event.clear()
        self.resume_data = None
        logger.info(
            "runtime.wait.resume_received sessionId=%s executionId=%s dataKeys=%s",
            self.context.session_id,
            self.context.execution_id,
            sorted(data.keys()),
        )
        return data

    def resume(self, form_data: Dict[str, Any]) -> None:
        logger.info(
            "runtime.resume.signal sessionId=%s executionId=%s dataKeys=%s",
            self.context.session_id,
            self.context.execution_id,
            sorted((form_data or {}).keys()),
        )
        self.resume_data = form_data
        self.resume_event.set()

    def request_suspend(self, reason: str) -> None:
        logger.info(
            "runtime.suspend.requested sessionId=%s executionId=%s reason=%s",
            self.context.session_id,
            self.context.execution_id,
            reason,
        )
        self.suspend_requested = True
        self.suspend_reason = reason

    def consume_suspend_request(self) -> Optional[str]:
        if not self.suspend_requested:
            return None
        reason = self.suspend_reason or "manual_suspend"
        self.suspend_requested = False
        self.suspend_reason = None
        logger.info(
            "runtime.suspend.consumed sessionId=%s executionId=%s reason=%s",
            self.context.session_id,
            self.context.execution_id,
            reason,
        )
        return reason

    def snapshot(self) -> Dict[str, Any]:
        return {
            "execution_id": self.context.execution_id,
            "session_id": self.context.session_id,
            "workflow_code": self.context.workflow_code,
            "workflow_version": self.context.workflow_version,
            "current_node_id": self.context.current_node_id,
            "status": self.context.status,
            "priority": self.context.priority,
            "experiment_id": self.context.experiment_id,
            "experiment_group": self.context.experiment_group,
            "dynamic_threshold": self.context.dynamic_threshold,
            "threshold_source": self.context.threshold_source,
            "requested_tool_code": self.context.requested_tool_code,
            "confirmed_tool_codes": list(self.context.confirmed_tool_codes),
            "routing_model_code": self.context.routing_model_code,
            "variables": dict(self.context.execution_variables),
            "runtime_metrics": dict(self.context.runtime_metrics),
            "plan_round": self.context.plan_round,
            "completed_nodes": list(self.context.completed_nodes),
            "skipped_nodes": list(self.context.skipped_nodes),
            "last_plan": dict(self.context.last_plan),
        }
