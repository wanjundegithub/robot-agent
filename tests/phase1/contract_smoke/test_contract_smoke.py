import os
import socket
import httpx
import pytest


def _is_tcp_open(host: str, port: int, timeout_sec: float = 0.3) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout_sec):
            return True
    except OSError:
        return False


def _base_url(env_key: str, default: str) -> str:
    return os.environ.get(env_key, default).rstrip("/")


@pytest.mark.contract
def test_java_health_or_openapi_smoke():
    """
    Phase 1 contract smoke test: Java service should be reachable.
    This test is intentionally tolerant: it will skip if service isn't running.
    """
    base = _base_url("PHASE1_JAVA_BASE_URL", "http://localhost:8080")
    host = base.replace("http://", "").replace("https://", "").split(":")[0]
    port = int(base.split(":")[-1]) if ":" in base.replace("http://", "").replace("https://", "") else 80

    if not _is_tcp_open(host, port):
        pytest.skip(f"Java not reachable at {base}. Set PHASE1_JAVA_BASE_URL and start Java service.")

    with httpx.Client(base_url=base, timeout=2.0) as c:
        # Try common endpoints; at least one should respond non-5xx.
        for path in ("/actuator/health", "/health", "/"):
            try:
                r = c.get(path)
                assert r.status_code < 500
                return
            except httpx.HTTPError:
                continue
        pytest.fail("Java reachable but no health-like endpoint responded.")


@pytest.mark.contract
def test_python_health_smoke():
    base = _base_url("PHASE1_PY_BASE_URL", "http://localhost:8000")
    host = base.replace("http://", "").replace("https://", "").split(":")[0]
    port = int(base.split(":")[-1]) if ":" in base.replace("http://", "").replace("https://", "") else 80

    if not _is_tcp_open(host, port):
        pytest.skip(f"Python not reachable at {base}. Set PHASE1_PY_BASE_URL and start Python runtime.")

    with httpx.Client(base_url=base, timeout=2.0) as c:
        r = c.get("/health")
        assert r.status_code == 200


@pytest.mark.contract
def test_phase1_contract_endpoints_exist_shape_only():
    """
    Enforces docs/phase1-contract.md (softly).
    If endpoints aren't implemented yet, we surface a clear failure once services are running.
    """
    java = _base_url("PHASE1_JAVA_BASE_URL", "http://localhost:8080")
    py = _base_url("PHASE1_PY_BASE_URL", "http://localhost:8000")

    # Skip if neither service is running: this is a developer-facing contract suite.
    if not _is_tcp_open("localhost", int(java.split(":")[-1])):
        pytest.skip("Java not running; skipping endpoint shape checks.")

    # Contract: Frontend -> Java message endpoint exists and returns contract-shaped JSON.
    with httpx.Client(base_url=java, timeout=3.0) as c:
        r = c.post("/api/sessions/test-session/messages", json={"message_id": "msg_001", "content": "hi", "attachments": []})
        assert r.status_code != 404, "Missing Phase 1 endpoint: POST /api/sessions/{sessionId}/messages"
        if r.status_code < 300:
            payload = r.json()
            for k in ("session_id", "execution_id", "workflow_code", "workflow_version", "status"):
                assert k in payload, f"Missing key `{k}` in message response payload: {payload}"
            # Latest target: status is lower-case (docs/phase1-contract.md).
            assert isinstance(payload["status"], str)
            assert payload["status"] == payload["status"].lower(), f"Expected lower-case status, got: {payload['status']}"

        r2 = c.post("/api/executions/test-exec/form-submit", json={"submit_id": "submit_001", "form_data": {"k": "v"}})
        assert r2.status_code != 404, "Missing Phase 1 endpoint: POST /api/executions/{executionId}/form-submit"
        if r2.status_code < 300:
            payload2 = r2.json()
            if "status" in payload2:
                assert isinstance(payload2["status"], str)
                assert payload2["status"] == payload2["status"].lower(), f"Expected lower-case status, got: {payload2['status']}"

    # Contract: Java -> Python execute endpoint exists (SSE).
    if _is_tcp_open("localhost", int(py.split(":")[-1])):
        with httpx.Client(base_url=py, timeout=3.0) as c:
            r = c.post(
                "/api/execute",
                headers={"Accept": "text/event-stream"},
                json={
                    "session_id": "sess_001",
                    "execution_id": "exec_001",
                    "workflow_code": "flight_booking",
                    "workflow_version": "1.0.0",
                    "input_variables": {"user_message": "hi"},
                },
            )
            assert r.status_code != 404, "Missing Phase 1 endpoint: POST /api/execute"
