from __future__ import annotations

from contextlib import nullcontext
from contextlib import contextmanager
from typing import Any, Dict

from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from prometheus_client import Counter, Gauge, Histogram, make_asgi_app

from .settings import settings


class WorkflowTelemetry:
    def __init__(self) -> None:
        self._initialized = False
        self._exporter_enabled = False
        self.tracer = trace.get_tracer(settings.otel_service_name)
        self.node_executions = Counter(
            "workflow_node_executions_total",
            "Total number of node executions",
            ["workflow_code", "node_type", "status"],
        )
        self.node_duration = Histogram(
            "workflow_node_duration_seconds",
            "Node execution duration",
            ["workflow_code", "node_type"],
            buckets=(0.01, 0.05, 0.1, 0.5, 1.0, 2.0, 5.0, 10.0),
        )
        self.intent_accuracy = Gauge("intent_accuracy", "Intent accuracy proxy", ["workflow_code"])
        self.task_completion_rate = Gauge("task_completion_rate", "Task completion rate", ["workflow_code"])
        self.human_intervention_rate = Gauge("human_intervention_rate", "Human intervention rate", ["workflow_code"])
        self.llm_cost_total = Counter("llm_cost_total_dollars", "Total LLM cost in dollars", ["model", "workflow"])
        self.token_consumption = Counter("token_consumption_total", "Total tokens consumed", ["model", "type", "workflow"])

    def initialize(self) -> None:
        if self._initialized or not settings.otel_enabled:
            return
        try:
            provider = TracerProvider(resource=Resource.create({"service.name": settings.otel_service_name}))
            exporter = OTLPSpanExporter(endpoint=settings.otel_exporter_endpoint, insecure=True)
            provider.add_span_processor(BatchSpanProcessor(exporter))
            trace.set_tracer_provider(provider)
            self.tracer = trace.get_tracer(settings.otel_service_name)
            self._initialized = True
            self._exporter_enabled = True
        except Exception:
            self._initialized = False
            self._exporter_enabled = False

    def span(self, name: str, attributes: Dict[str, Any]):
        if not settings.otel_enabled:
            return nullcontext()
        self.initialize()
        @contextmanager
        def _managed_span():
            with self.tracer.start_as_current_span(name) as span:
                for key, value in attributes.items():
                    span.set_attribute(key, value)
                yield span
        return _managed_span()

    def record_node(self, workflow_code: str, node_type: str, status: str, duration_ms: int) -> None:
        self.node_executions.labels(workflow_code=workflow_code, node_type=node_type, status=status).inc()
        self.node_duration.labels(workflow_code=workflow_code, node_type=node_type).observe(duration_ms / 1000)

    def record_llm_cost(self, model: str, workflow_code: str, input_tokens: int, output_tokens: int, cost: float) -> None:
        self.token_consumption.labels(model=model, type="input", workflow=workflow_code).inc(input_tokens)
        self.token_consumption.labels(model=model, type="output", workflow=workflow_code).inc(output_tokens)
        self.llm_cost_total.labels(model=model, workflow=workflow_code).inc(cost)

    def exporter_status(self) -> str:
        return "otlp" if self._exporter_enabled else "disabled"

workflow_telemetry = WorkflowTelemetry()
metrics_app = make_asgi_app()
