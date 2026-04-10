import asyncio
from dataclasses import dataclass, field
from typing import Any, Dict, Optional, AsyncIterator

from .context import ExecutionContext
from .events import sse_format
from .security import mask_sensitive_fields


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
        self.queue.put_nowait(sse_format(event_id, event_type, safe_data))

    async def stream(self) -> AsyncIterator[str]:
        while True:
            if self.done and self.queue.empty():
                break
            item = await self.queue.get()
            yield item

    def mark_done(self) -> None:
        self.done = True

    def prepare_wait(self) -> None:
        self.resume_event.clear()
        self.resume_data = None

    async def wait_for_resume(self) -> Dict[str, Any]:
        await self.resume_event.wait()
        data = self.resume_data or {}
        self.resume_event.clear()
        self.resume_data = None
        return data

    def resume(self, form_data: Dict[str, Any]) -> None:
        self.resume_data = form_data
        self.resume_event.set()

    def request_suspend(self, reason: str) -> None:
        self.suspend_requested = True
        self.suspend_reason = reason

    def consume_suspend_request(self) -> Optional[str]:
        if not self.suspend_requested:
            return None
        reason = self.suspend_reason or "manual_suspend"
        self.suspend_requested = False
        self.suspend_reason = None
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
            "variables": dict(self.context.execution_variables),
        }
