from typing import Any, Dict, Optional

from pydantic import BaseModel, Field


class ExecuteRequest(BaseModel):
    session_id: str = Field(..., description="Session identifier from Java")
    execution_id: str = Field(..., description="Execution identifier from Java")
    workflow_code: str = Field(..., description="Workflow code")
    workflow_id: Optional[int] = Field(default=None, description="Workflow identifier")
    workflow_version: str = Field(..., description="Workflow version")
    message_id: Optional[str] = Field(default=None, description="Client message id for idempotency")
    priority: int = Field(default=0, description="Routing priority")
    route_decision: str = Field(default="start", description="Route decision name")
    route_reason: Optional[str] = Field(default=None, description="Route reason")
    route_confidence: float = Field(default=0.0, description="Route confidence")
    user_id: str = Field(default="anonymous", description="User id")
    experiment_id: Optional[str] = Field(default=None, description="Experiment id")
    experiment_group: Optional[str] = Field(default=None, description="Experiment group")
    dynamic_threshold: Optional[float] = Field(default=None, description="Dynamic threshold")
    threshold_source: Optional[str] = Field(default=None, description="Threshold source")
    requested_tool_code: Optional[str] = Field(default=None, description="High-risk tool requested by Java")
    confirmed_tool_codes: list[str] = Field(default_factory=list, description="Confirmed high-risk tools")
    workflow_definition: Dict[str, Any] = Field(default_factory=dict)
    entry_rule: Dict[str, Any] = Field(default_factory=dict)
    workflow_config: Dict[str, Any] = Field(default_factory=dict)
    workflow_catalog: Dict[str, Dict[str, Any]] = Field(default_factory=dict)
    provider_configs: list[Dict[str, Any]] = Field(default_factory=list)
    model_records: list[Dict[str, Any]] = Field(default_factory=list)
    routing_model_code: Optional[str] = Field(default=None)
    input_variables: Dict[str, Any] = Field(default_factory=dict)


class FormSubmitRequest(BaseModel):
    submit_id: str = Field(..., description="Form submit id")
    form_data: Dict[str, Any] = Field(default_factory=dict)


class ExecuteStatusResponse(BaseModel):
    execution_id: str
    status: str
    current_node_id: Optional[str] = None
    form_definition: Optional[Dict[str, Any]] = None


class SuspendExecutionRequest(BaseModel):
    reason: str = Field(default="manual_suspend", description="Suspend reason")


class ResumeExecutionResponse(BaseModel):
    execution_id: str
    status: str
    form_definition: Optional[Dict[str, Any]] = None


class ThresholdResolveRequest(BaseModel):
    workflow_code: str
    intent_code: str
    confidence: float
    message: str = ""


class RecommendationRequest(BaseModel):
    workflow_code: str
    message: str = ""


class RagEvaluationRequest(BaseModel):
    dataset: Optional[list[Dict[str, Any]]] = None


class IntentClassificationRequest(BaseModel):
    message: str
    candidate_workflows: list[Dict[str, Any]] = Field(default_factory=list)
    provider_configs: list[Dict[str, Any]] = Field(default_factory=list)
    model_records: list[Dict[str, Any]] = Field(default_factory=list)
    routing_model_code: str
