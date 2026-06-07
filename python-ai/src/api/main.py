import asyncio
import logging

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse

from .models import (
    ExecuteRequest,
    ExecuteStatusResponse,
    FormSubmitRequest,
    IntentClassificationRequest,
    RagEvaluationRequest,
    RecommendationRequest,
    ResumeExecutionResponse,
    SuspendExecutionRequest,
    ThresholdResolveRequest,
    WelcomeDecisionRequest,
    WelcomeDecisionResponse,
)
from src.core.evaluation import rag_evaluator
from src.core.events import utc_now_iso
from src.core.idempotency import (
    get_idempotency_backend,
    get_idempotency_store,
    initialize_idempotency_store,
)
from src.core.knowledge_store import get_knowledge_backend, initialize_knowledge_store
from src.core.logging_utils import configure_logging
from src.core.model_runtime import classify_intent_with_model_code
from src.core.optimization import dynamic_threshold_manager, subflow_recommendation_service
from src.core.protection import ProtectionError, runtime_protection_manager
from src.core.registry import ExecutionRegistry
from src.core.scheduler import WorkflowScheduler
from src.core.telemetry import metrics_app, workflow_telemetry
from src.core.welcome_decision import decide_workflow_welcome


configure_logging()
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
    logger.info("python-ai service startup begin")
    initialize_idempotency_store()
    initialize_knowledge_store()
    workflow_telemetry.initialize()
    logger.info("python-ai service startup complete")


@app.post("/api/execute")
async def execute(request: ExecuteRequest):
    logger.info(
        "execute.request sessionId=%s executionId=%s workflowCode=%s workflowVersion=%s messageId=%s routeDecision=%s providerCount=%s modelRecordCount=%s hasWorkflowDefinition=%s",
        request.session_id,
        request.execution_id,
        request.workflow_code,
        request.workflow_version,
        request.message_id,
        request.route_decision,
        len(request.provider_configs),
        len(request.model_records),
        bool(request.workflow_definition),
    )
    try:
        runtime = await registry.create_execution(request.model_dump())
    except ProtectionError as exc:
        logger.warning(
            "execute.protection_rejected sessionId=%s executionId=%s payloadKeys=%s",
            request.session_id,
            request.execution_id,
            list(exc.payload.keys()) if exc.payload else [],
        )
        status_code = 429 if exc.payload.get("scope") else 503
        raise HTTPException(status_code=status_code, detail=exc.payload or str(exc))
    except Exception as exc:
        logger.exception(
            "execute.create_failed sessionId=%s executionId=%s workflowCode=%s workflowVersion=%s",
            request.session_id,
            request.execution_id,
            request.workflow_code,
            request.workflow_version,
        )
        raise HTTPException(status_code=400, detail=str(exc))

    task = asyncio.create_task(scheduler.run(runtime))

    def _log_scheduler_done(done_task: asyncio.Task) -> None:
        if done_task.cancelled():
            logger.warning(
                "execute.scheduler_task.cancelled sessionId=%s executionId=%s",
                request.session_id,
                request.execution_id,
            )
            return
        error = done_task.exception()
        if error:
            logger.error(
                "execute.scheduler_task.failed sessionId=%s executionId=%s",
                request.session_id,
                request.execution_id,
                exc_info=(type(error), error, error.__traceback__),
            )
            return
        logger.info(
            "execute.scheduler_task.completed sessionId=%s executionId=%s",
            request.session_id,
            request.execution_id,
        )

    task.add_done_callback(_log_scheduler_done)
    logger.info("execute.stream.open sessionId=%s executionId=%s", request.session_id, request.execution_id)
    return StreamingResponse(runtime.stream(), media_type="text/event-stream")


@app.post("/api/executions/{execution_id}/form-submit")
async def submit_form(execution_id: str, request: FormSubmitRequest):
    submit_key = f"form_submit:{execution_id}:{request.submit_id}"
    logger.info(
        "form_submit.request executionId=%s submitId=%s fieldCount=%s",
        execution_id,
        request.submit_id,
        len(request.form_data),
    )
    cached = get_idempotency_store().get_json(submit_key)
    if cached is not None:
        logger.info("form_submit.idempotency_hit executionId=%s submitId=%s", execution_id, request.submit_id)
        return cached

    try:
        runtime = await registry.resume_execution(execution_id, request.form_data)
        response = {"execution_id": execution_id, "status": "running"}
        get_idempotency_store().set_json(submit_key, response, 86400)
        runtime.last_form_response = response
        logger.info("form_submit.resumed executionId=%s submitId=%s status=%s", execution_id, request.submit_id, response["status"])
        return response
    except Exception as exc:
        logger.exception("form_submit.failed executionId=%s submitId=%s", execution_id, request.submit_id)
        raise HTTPException(status_code=404, detail=str(exc))


@app.post("/api/executions/{execution_id}/suspend")
async def suspend_execution(execution_id: str, request: SuspendExecutionRequest):
    logger.info("execution.suspend.request executionId=%s reason=%s", execution_id, request.reason)
    runtime = await registry.get_execution(execution_id)
    if not runtime:
        logger.warning("execution.suspend.not_found executionId=%s", execution_id)
        raise HTTPException(status_code=404, detail="Execution not found")

    runtime.request_suspend(request.reason)
    logger.info("execution.suspend.accepted executionId=%s reason=%s", execution_id, request.reason)
    return {
        "execution_id": execution_id,
        "status": "suspend_requested",
        "reason": request.reason,
        "snapshot": runtime.snapshot(),
    }


@app.post("/api/executions/{execution_id}/resume")
async def resume_execution(execution_id: str):
    logger.info("execution.resume.request executionId=%s", execution_id)
    runtime = await registry.get_execution(execution_id)
    if not runtime:
        logger.warning("execution.resume.not_found executionId=%s", execution_id)
        raise HTTPException(status_code=404, detail="Execution not found")

    if runtime.context.status == "waiting_user":
        logger.info("execution.resume.waiting_user executionId=%s hasFormDefinition=%s", execution_id, bool(runtime.last_form_definition))
        response = ResumeExecutionResponse(
            execution_id=execution_id,
            status="waiting_user",
            form_definition=runtime.last_form_definition,
        )
        return response.model_dump()

    runtime.resume({})
    response = ResumeExecutionResponse(execution_id=execution_id, status="running")
    logger.info("execution.resume.resumed executionId=%s status=%s", execution_id, response.status)
    return response.model_dump()


@app.get("/api/executions/{execution_id}/status")
async def get_execution_status(execution_id: str):
    logger.info("execution.status.request executionId=%s", execution_id)
    runtime = await registry.get_execution(execution_id)
    if not runtime:
        logger.warning("execution.status.not_found executionId=%s", execution_id)
        raise HTTPException(status_code=404, detail="Execution not found")
    context = runtime.context
    response = ExecuteStatusResponse(
        execution_id=context.execution_id,
        status=context.status,
        current_node_id=context.current_node_id,
        form_definition=runtime.last_form_definition,
    )
    logger.info(
        "execution.status.response executionId=%s sessionId=%s status=%s currentNodeId=%s hasFormDefinition=%s",
        context.execution_id,
        context.session_id,
        context.status,
        context.current_node_id,
        bool(runtime.last_form_definition),
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


@app.get("/api/phase5/runtime-status")
async def runtime_status():
    return runtime_protection_manager.build_runtime_status()


@app.post("/api/phase5/intents/classify")
async def classify_intent(request: IntentClassificationRequest):
    provider_configs = {
        str(item.get("provider_code")): item
        for item in request.provider_configs
        if item.get("provider_code")
    }
    model_records = {
        str(item.get("model_code")): item
        for item in request.model_records
        if item.get("model_code")
    }
    return await classify_intent_with_model_code(
        message=request.message,
        candidate_workflows=request.candidate_workflows,
        routing_model_code=request.routing_model_code,
        provider_configs=provider_configs,
        model_records=model_records,
        system_prompts=request.system_prompts,
    )


@app.post("/api/phase5/workflow-welcome/decide", response_model=WelcomeDecisionResponse)
async def decide_workflow_welcome_api(request: WelcomeDecisionRequest):
    provider_configs = {
        str(item.get("provider_code")): item
        for item in request.provider_configs
        if item.get("provider_code")
    }
    model_records = {
        str(item.get("model_code")): item
        for item in request.model_records
        if item.get("model_code")
    }
    result = await decide_workflow_welcome(
        session_id=request.session_id,
        workflow_code=request.workflow_code,
        workflow_version=request.workflow_version,
        workflow_summary=request.workflow_summary,
        session_context=request.session_context,
        provider_configs=provider_configs,
            model_records=model_records,
            routing_model_code=request.routing_model_code,
            system_prompts=request.system_prompts,
        )
    return WelcomeDecisionResponse(**result)


@app.post("/api/phase4/route-thresholds/resolve")
async def resolve_dynamic_threshold(request: ThresholdResolveRequest):
    decision = dynamic_threshold_manager.resolve(
        workflow_code=request.workflow_code,
        intent_code=request.intent_code,
        confidence=request.confidence,
        message=request.message,
    )
    return {
        "workflow_code": decision.workflow_code,
        "intent_code": decision.intent_code,
        "threshold": decision.threshold,
        "threshold_source": decision.threshold_source,
        "accepted": decision.accepted,
    }


@app.post("/api/phase4/subflow-recommendations")
async def recommend_subflows(request: RecommendationRequest):
    recommendations = subflow_recommendation_service.recommend(
        workflow_code=request.workflow_code,
        message=request.message,
    )
    return {
        "workflow_code": request.workflow_code,
        "recommendations": recommendations,
    }


@app.post("/api/phase4/evaluations/rag")
async def evaluate_rag(request: RagEvaluationRequest):
    return rag_evaluator.evaluate_dataset(request.dataset)
