from typing import Dict, Any, Optional, List
from dataclasses import dataclass, field
from datetime import datetime
from uuid import uuid4


@dataclass
class ExecutionContext:
    """Execution context for a single workflow run."""

    execution_id: str
    session_id: str
    workflow_code: str
    workflow_version: str
    message_id: Optional[str] = None
    trace_id: str = field(default_factory=lambda: uuid4().hex)
    priority: int = 0
    route_decision: str = "start"
    route_reason: Optional[str] = None
    route_confidence: float = 0.0
    user_id: str = "anonymous"
    experiment_id: Optional[str] = None
    experiment_group: Optional[str] = None
    dynamic_threshold: Optional[float] = None
    threshold_source: Optional[str] = None
    requested_tool_code: Optional[str] = None
    confirmed_tool_codes: List[str] = field(default_factory=list)
    workflow_config: Dict[str, Any] = field(default_factory=dict)
    workflow_catalog: Dict[str, Dict[str, Any]] = field(default_factory=dict)
    provider_configs: Dict[str, Dict[str, Any]] = field(default_factory=dict)
    model_profiles: Dict[str, Dict[str, Any]] = field(default_factory=dict)
    intent_profile_code: Optional[str] = None

    current_node_id: Optional[str] = None
    status: str = "pending"

    session_variables: Dict[str, Any] = field(default_factory=dict)
    execution_variables: Dict[str, Any] = field(default_factory=dict)
    execution_stack: List[Dict[str, Any]] = field(default_factory=list)
    runtime_metrics: Dict[str, Any] = field(default_factory=dict)

    created_at: datetime = field(default_factory=datetime.now)
    updated_at: datetime = field(default_factory=datetime.now)

    def add_session_variable(self, key: str, value: Any) -> None:
        self.session_variables[key] = value
        self.updated_at = datetime.now()

    def add_execution_variable(self, key: str, value: Any) -> None:
        self.execution_variables[key] = value
        self.updated_at = datetime.now()

    def add_execution_variables(self, values: Dict[str, Any]) -> None:
        for key, value in values.items():
            self.execution_variables[key] = value
        self.updated_at = datetime.now()

    def get_variable(self, key: str, default: Any = None) -> Any:
        if key in self.execution_variables:
            return self.execution_variables.get(key, default)
        return self.session_variables.get(key, default)

    def update_node_position(self, node_id: str) -> None:
        self.current_node_id = node_id
        self.updated_at = datetime.now()

    def add_runtime_metric(self, key: str, value: Any) -> None:
        self.runtime_metrics[key] = value
        self.updated_at = datetime.now()
