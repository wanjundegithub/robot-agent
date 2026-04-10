from typing import Any, Dict, Optional

from pydantic import BaseModel, Field


class ExecuteRequest(BaseModel):
    session_id: str = Field(..., description="Session identifier from Java")
    execution_id: str = Field(..., description="Execution identifier from Java")
    workflow_code: str = Field(..., description="Workflow code")
    workflow_version: str = Field(..., description="Workflow version")
    message_id: Optional[str] = Field(default=None, description="Client message id for idempotency")
    priority: int = Field(default=0, description="Routing priority")
    route_decision: str = Field(default="start", description="Route decision name")
    route_reason: Optional[str] = Field(default=None, description="Route reason")
    route_confidence: float = Field(default=0.0, description="Route confidence")
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
