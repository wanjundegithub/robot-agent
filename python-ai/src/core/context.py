from typing import Dict, Any, Optional, List
from dataclasses import dataclass, field
from datetime import datetime
from uuid import uuid4


@dataclass
class GraphFrame:
    graph_id: str
    parent_graph_id: Optional[str] = None
    parent_node_id: Optional[str] = None
    output_mapping: Dict[str, Any] = field(default_factory=dict)


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
    completed_nodes: List[str] = field(default_factory=list)
    skipped_nodes: List[str] = field(default_factory=list)
    failed_nodes: List[str] = field(default_factory=list)
    node_outputs: Dict[str, Dict[str, Any]] = field(default_factory=dict)
    plan_round: int = 0
    last_plan: Dict[str, Any] = field(default_factory=dict)
    current_graph_id: Optional[str] = None
    graph_stack: List[GraphFrame] = field(default_factory=list)
    available_targets: List[str] = field(default_factory=list)
    node_input_snapshot: Dict[str, Dict[str, Any]] = field(default_factory=dict)
    node_output_snapshot: Dict[str, Dict[str, Any]] = field(default_factory=dict)

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

    def record_plan(self, plan: Dict[str, Any]) -> None:
        self.plan_round += 1
        self.last_plan = dict(plan)
        self.updated_at = datetime.now()

    def record_completed_node(self, node_id: str, output: Optional[Dict[str, Any]] = None) -> None:
        self.completed_nodes.append(node_id)
        if output is not None:
            self.node_outputs[node_id] = dict(output)
        self.updated_at = datetime.now()

    def record_skipped_node(self, node_id: str, reason: str) -> None:
        self.skipped_nodes.append(node_id)
        self.node_outputs[node_id] = {"skip_reason": reason}
        self.updated_at = datetime.now()

    def record_failed_node(self, node_id: str, error: str) -> None:
        self.failed_nodes.append(node_id)
        self.node_outputs[node_id] = {"error": error}
        self.updated_at = datetime.now()

    def enter_graph(
        self,
        graph_id: str,
        parent_graph_id: Optional[str] = None,
        parent_node_id: Optional[str] = None,
        output_mapping: Optional[Dict[str, Any]] = None,
    ) -> None:
        frame = GraphFrame(
            graph_id=graph_id,
            parent_graph_id=parent_graph_id,
            parent_node_id=parent_node_id,
            output_mapping=dict(output_mapping or {}),
        )
        self.graph_stack.append(frame)
        self.current_graph_id = graph_id
        self.updated_at = datetime.now()

    def exit_graph(self) -> Optional[GraphFrame]:
        if not self.graph_stack:
            return None
        frame = self.graph_stack.pop()
        self.current_graph_id = self.graph_stack[-1].graph_id if self.graph_stack else None
        self.updated_at = datetime.now()
        return frame

    def set_available_targets(self, targets: List[str]) -> None:
        self.available_targets = list(targets)
        self.updated_at = datetime.now()

    def record_node_input_snapshot(self, node_id: str, snapshot: Dict[str, Any]) -> None:
        self.node_input_snapshot[node_id] = dict(snapshot)
        self.updated_at = datetime.now()

    def record_node_output_snapshot(self, node_id: str, snapshot: Dict[str, Any]) -> None:
        self.node_output_snapshot[node_id] = dict(snapshot)
        self.updated_at = datetime.now()
