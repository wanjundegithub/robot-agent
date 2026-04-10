import asyncio
import logging

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse

from .models import (
    ExecuteRequest,
    ExecuteStatusResponse,
    FormSubmitRequest,
    ResumeExecutionResponse,
    SuspendExecutionRequest,
)
from src.core.events import utc_now_iso
from src.core.idempotency import (
    get_idempotency_backend,
    get_idempotency_store,
    initialize_idempotency_store,
)
from src.core.knowledge_store import get_knowledge_backend, initialize_knowledge_store
from src.core.registry import ExecutionRegistry
from src.core.scheduler import WorkflowScheduler
from src.core.telemetry import metrics_app, workflow_telemetry


logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="Workflow Engine API", version="3.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
app.mount("/metrics", metrics_app)

registry = ExecutionRegistry()
scheduler = WorkflowScheduler()


@app.on_event("startup")
async def startup() -> None:
    initialize_idempotency_store()
    initialize_knowledge_store()
    workflow_telemetry.initialize()


@app.post("/api/execute")
async def execute(request: ExecuteRequest):
    try:
        runtime = await registry.create_execution(request.model_dump())
    except Exception as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    asyncio.create_task(scheduler.run(runtime))
    return StreamingResponse(runtime.stream(), media_type="text/event-stream")


@app.post("/api/executions/{execution_id}/form-submit")
async def submit_form(execution_id: str, request: FormSubmitRequest):
    submit_key = f"form_submit:{execution_id}:{request.submit_id}"
    cached = get_idempotency_store().get_json(submit_key)
    if cached is not None:
        return cached

    try:
        runtime = await registry.resume_execution(execution_id, request.form_data)
        response = {"execution_id": execution_id, "status": "running"}
        get_idempotency_store().set_json(submit_key, response, 86400)
        runtime.last_form_response = response
        return response
    except Exception as exc:
        raise HTTPException(status_code=404, detail=str(exc))


@app.post("/api/executions/{execution_id}/suspend")
async def suspend_execution(execution_id: str, request: SuspendExecutionRequest):
    runtime = await registry.get_execution(execution_id)
    if not runtime:
        raise HTTPException(status_code=404, detail="Execution not found")

    runtime.request_suspend(request.reason)
    return {
        "execution_id": execution_id,
        "status": "suspend_requested",
        "reason": request.reason,
        "snapshot": runtime.snapshot(),
    }


@app.post("/api/executions/{execution_id}/resume")
async def resume_execution(execution_id: str):
    runtime = await registry.get_execution(execution_id)
    if not runtime:
        raise HTTPException(status_code=404, detail="Execution not found")

    if runtime.context.status == "waiting_user":
        response = ResumeExecutionResponse(
            execution_id=execution_id,
            status="waiting_user",
            form_definition=runtime.last_form_definition,
        )
        return response.model_dump()

    runtime.resume({})
    response = ResumeExecutionResponse(execution_id=execution_id, status="running")
    return response.model_dump()


@app.get("/api/executions/{execution_id}/status")
async def get_execution_status(execution_id: str):
    runtime = await registry.get_execution(execution_id)
    if not runtime:
        raise HTTPException(status_code=404, detail="Execution not found")
    context = runtime.context
    response = ExecuteStatusResponse(
        execution_id=context.execution_id,
        status=context.status,
        current_node_id=context.current_node_id,
        form_definition=runtime.last_form_definition,
    )
    return response.model_dump()


@app.get("/health")
async def health_check():
    return {
        "status": "healthy",
        "service": "workflow-engine",
        "time": utc_now_iso(),
        "backends": {
            "idempotency": get_idempotency_backend(),
            "knowledge": get_knowledge_backend(),
            "telemetry": workflow_telemetry.exporter_status(),
        },
    }
