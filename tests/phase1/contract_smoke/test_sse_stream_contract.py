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
def test_python_execute_is_sse_stream_when_running():
    """
    Architecture + contract requires:
      POST /api/execute
      Accept: text/event-stream
      Response: SSE stream with `event:` + `data:` lines.
    """
    base = _base_url("PHASE1_PY_BASE_URL", "http://localhost:8000")
    host = base.replace("http://", "").replace("https://", "").split(":")[0]
    port = int(base.split(":")[-1]) if ":" in base.replace("http://", "").replace("https://", "") else 80

    if not _is_tcp_open(host, port):
        pytest.skip("Python not running; skipping SSE contract test.")

    with httpx.Client(base_url=base, timeout=httpx.Timeout(5.0, read=5.0)) as c:
        with c.stream(
            "POST",
            "/api/execute",
            headers={"Accept": "text/event-stream"},
            json={
                "session_id": "sess_contract",
                "execution_id": "exec_contract",
                "workflow_code": "flight_booking",
                "workflow_version": "1.0.0",
                "input_variables": {"user_message": "hi"},
            },
        ) as r:
            assert r.status_code != 404, "Missing Phase 1 endpoint: POST /api/execute"
            assert r.status_code < 500

            content_type = r.headers.get("content-type", "")
            assert "text/event-stream" in content_type, f"Expected SSE content-type, got: {content_type}"

            buf = ""
            for line in r.iter_lines():
                if line is None:
                    continue
                s = line.strip()
                if not s:
                    continue
                buf += s + "\n"
                if "event:" in buf and "data:" in buf:
                    break

            assert "event:" in buf and "data:" in buf, f"Did not observe SSE event framing. Got:\n{buf}"

