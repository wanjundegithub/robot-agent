import json
from datetime import datetime, timezone
from typing import Any, Dict


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def sse_format(event_id: int, event_type: str, data: Dict[str, Any]) -> str:
    payload = json.dumps(data, ensure_ascii=True)
    return f"id: {event_id}\nevent: {event_type}\ndata: {payload}\n\n"
