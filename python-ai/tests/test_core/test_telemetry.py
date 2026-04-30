from src.core.telemetry import workflow_telemetry


def test_workflow_telemetry_keeps_trace_exporter_disabled():
    workflow_telemetry.initialize()

    assert workflow_telemetry.exporter_status() == "disabled"
    with workflow_telemetry.span("execute_node", {"node.id": "node_1"}):
        pass
