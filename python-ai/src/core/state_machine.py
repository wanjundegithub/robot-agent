from typing import Dict, Any, Optional, List
from enum import Enum
import logging


class ExecutionStatus(Enum):
    PENDING = "pending"
    ROUTING = "routing"
    RUNNING = "running"
    WAITING_USER = "waiting_user"
    WAITING_TOOL = "waiting_tool"
    SUSPENDED = "suspended"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class TransitionEvent(Enum):
    START = "start"
    ROUTE = "route"
    WAIT_USER = "wait_user"
    WAIT_TOOL = "wait_tool"
    SUSPEND = "suspend"
    RESUME = "resume"
    COMPLETE = "complete"
    FAIL = "fail"
    CANCEL = "cancel"
    TIMEOUT = "timeout"


class ExecutionStateMachine:
    """执行状态机"""

    def __init__(self):
        self.logger = logging.getLogger(__name__)
        self.context = None
        self.transitions = self._init_transitions()

    def _init_transitions(self) -> Dict[ExecutionStatus, List[TransitionEvent]]:
        """初始化状态转换规则"""
        return {
            ExecutionStatus.PENDING: [TransitionEvent.START, TransitionEvent.CANCEL],
            ExecutionStatus.ROUTING: [TransitionEvent.ROUTE, TransitionEvent.FAIL, TransitionEvent.CANCEL],
            ExecutionStatus.RUNNING: [
                TransitionEvent.WAIT_USER,
                TransitionEvent.WAIT_TOOL,
                TransitionEvent.SUSPEND,
                TransitionEvent.COMPLETE,
                TransitionEvent.FAIL,
                TransitionEvent.CANCEL,
                TransitionEvent.TIMEOUT
            ],
            ExecutionStatus.WAITING_USER: [
                TransitionEvent.RESUME,
                TransitionEvent.SUSPEND,
                TransitionEvent.FAIL,
                TransitionEvent.CANCEL
            ],
            ExecutionStatus.WAITING_TOOL: [
                TransitionEvent.RESUME,
                TransitionEvent.SUSPEND,
                TransitionEvent.FAIL,
                TransitionEvent.CANCEL,
                TransitionEvent.TIMEOUT
            ],
            ExecutionStatus.SUSPENDED: [TransitionEvent.RESUME, TransitionEvent.CANCEL, TransitionEvent.FAIL]
        }

    def set_context(self, context):
        self.context = context

    def can_transition(self, event: TransitionEvent) -> bool:
        if not self.context:
            return False
        current_state = ExecutionStatus(self.context.status)
        return event in self.transitions.get(current_state, [])

    def transition(self, event: TransitionEvent) -> bool:
        if not self.can_transition(event):
            return False

        state_map = {
            TransitionEvent.START: ExecutionStatus.ROUTING,
            TransitionEvent.ROUTE: ExecutionStatus.RUNNING,
            TransitionEvent.WAIT_USER: ExecutionStatus.WAITING_USER,
            TransitionEvent.WAIT_TOOL: ExecutionStatus.WAITING_TOOL,
            TransitionEvent.SUSPEND: ExecutionStatus.SUSPENDED,
            TransitionEvent.RESUME: ExecutionStatus.RUNNING,
            TransitionEvent.COMPLETE: ExecutionStatus.COMPLETED,
            TransitionEvent.FAIL: ExecutionStatus.FAILED,
            TransitionEvent.CANCEL: ExecutionStatus.CANCELLED,
            TransitionEvent.TIMEOUT: ExecutionStatus.CANCELLED
        }

        self.context.status = state_map[event].value
        return True
