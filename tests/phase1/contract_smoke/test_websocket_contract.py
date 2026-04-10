import os
import asyncio
import pytest
import websockets


def _ws_url() -> str | None:
    """
    Contract suggests `/ws/executions` but architecture doesn't mandate a path.
    We only run this test if PHASE1_WS_URL is set, e.g.:
      ws://localhost:8080/ws/executions?execution_id=exec_001
    """
    v = os.environ.get("PHASE1_WS_URL")
    if not v:
        return None
    return v


@pytest.mark.contract
def test_ws_event_envelope_shape_if_configured():
    url = _ws_url()
    if not url:
        pytest.skip("Set PHASE1_WS_URL to enable WebSocket envelope smoke test.")

    async def _run():
        async with websockets.connect(url, open_timeout=2) as ws:
            msg = await asyncio.wait_for(ws.recv(), timeout=5)
            assert isinstance(msg, (str, bytes))
            # Keep it minimal: ensure we can receive something. Strict schema checks
            # will be added once backend-java freezes the WebSocket payloads.
            if isinstance(msg, bytes):
                assert msg
            else:
                assert msg.strip()

    asyncio.run(_run())

