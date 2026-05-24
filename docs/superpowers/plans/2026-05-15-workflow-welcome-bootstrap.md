# Workflow Welcome Decision Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a safe Python welcome decision endpoint that returns a normalized greet/no-greet result for Java bootstrap.

**Architecture:** Keep the FastAPI surface thin, put request/response normalization in `src/api/models.py`, and add a dedicated welcome decision helper in `src/core/welcome_decision.py` that reuses `execute_model_completion` with a fixed system prompt. The helper should never treat workflow prompt text as system instructions and should degrade to a safe non-greeting result on model/config failures.

**Tech Stack:** FastAPI, Pydantic, pytest, existing model runtime and logging utilities.

---

### Task 1: Add request/response models

**Files:**
- Modify: `python-ai/src/api/models.py`
- Test: `python-ai/tests/test_api/test_welcome_decision.py`

- [ ] **Step 1: Write the failing test**

```python
from src.api.models import WelcomeDecisionRequest


def test_welcome_decision_request_model_accepts_expected_fields():
    request = WelcomeDecisionRequest(
        session_id="session-1",
        workflow_code="hotel_booking",
        workflow_version="1.0.0",
        workflow_summary={"name": "酒店预订助手"},
        session_context={"trigger": "ws_bootstrap"},
        provider_configs=[],
        model_records=[],
        routing_model_code="general-chat-v1",
    )
    assert request.session_id == "session-1"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest python-ai/tests/test_api/test_welcome_decision.py::test_welcome_decision_request_model_accepts_expected_fields -v`
Expected: FAIL with missing import / missing model.

- [ ] **Step 3: Write minimal implementation**

```python
class WelcomeDecisionRequest(BaseModel):
    session_id: str
    workflow_code: str
    workflow_version: str
    workflow_summary: Dict[str, Any] = Field(default_factory=dict)
    session_context: Dict[str, Any] = Field(default_factory=dict)
    provider_configs: list[Dict[str, Any]] = Field(default_factory=list)
    model_records: list[Dict[str, Any]] = Field(default_factory=list)
    routing_model_code: str


class WelcomeDecisionResponse(BaseModel):
    should_greet: bool
    message: str = ""
    reason: str = ""
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest python-ai/tests/test_api/test_welcome_decision.py::test_welcome_decision_request_model_accepts_expected_fields -v`
Expected: PASS

### Task 2: Implement welcome decision helper

**Files:**
- Create: `python-ai/src/core/welcome_decision.py`
- Modify: `python-ai/src/core/model_runtime.py`
- Test: `python-ai/tests/test_core/test_welcome_decision.py`

- [ ] **Step 1: Write the failing test**

```python
import json
from unittest.mock import AsyncMock, patch

import pytest

from src.core.welcome_decision import decide_workflow_welcome


@pytest.mark.asyncio
async def test_decide_workflow_welcome_returns_greeting_when_model_says_yes():
    provider_configs = {"openai-compatible-prod": {"provider_code": "openai-compatible-prod", "provider_type": "openai_compatible", "base_url": "https://llm.example.com/v1"}}
    model_records = {"welcome-model": {"model_code": "welcome-model", "provider_code": "openai-compatible-prod", "upstream_model_code": "qwen-plus"}}
    mocked_completion = AsyncMock(return_value=json.dumps({"should_greet": True, "message": "您好！", "reason": "首次打开"}, ensure_ascii=False))
    with patch("src.core.welcome_decision.execute_model_completion", new=mocked_completion):
        result = await decide_workflow_welcome(session_id="session-1", workflow_code="hotel_booking", workflow_version="1.0.0", workflow_summary={"name": "酒店预订助手"}, session_context={"trigger": "ws_bootstrap"}, provider_configs=provider_configs, model_records=model_records, routing_model_code="welcome-model")
    assert result == {"should_greet": True, "message": "您好！", "reason": "首次打开"}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest python-ai/tests/test_core/test_welcome_decision.py::test_decide_workflow_welcome_returns_greeting_when_model_says_yes -v`
Expected: FAIL with missing module/function.

- [ ] **Step 3: Write minimal implementation**

```python
async def decide_workflow_welcome(...):
    content = await execute_model_completion(...)
    parsed = json.loads(content)
    return {"should_greet": bool(parsed.get("should_greet")), "message": str(parsed.get("message") or ""), "reason": str(parsed.get("reason") or "")}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest python-ai/tests/test_core/test_welcome_decision.py::test_decide_workflow_welcome_returns_greeting_when_model_says_yes -v`
Expected: PASS

### Task 3: Wire the API endpoint

**Files:**
- Modify: `python-ai/src/api/main.py`
- Test: `python-ai/tests/test_api/test_welcome_decision.py`

- [ ] **Step 1: Write the failing test**

```python
from fastapi.testclient import TestClient
from src.api.main import app


def test_welcome_decision_endpoint_returns_response():
    client = TestClient(app)
    response = client.post("/api/phase5/workflow-welcome/decide", json={...})
    assert response.status_code == 200
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest python-ai/tests/test_api/test_welcome_decision.py::test_welcome_decision_endpoint_returns_response -v`
Expected: FAIL with 404 / missing route.

- [ ] **Step 3: Write minimal implementation**

```python
@app.post("/api/phase5/workflow-welcome/decide")
async def decide_workflow_welcome_endpoint(request: WelcomeDecisionRequest):
    return await decide_workflow_welcome(...)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest python-ai/tests/test_api/test_welcome_decision.py::test_welcome_decision_endpoint_returns_response -v`
Expected: PASS

### Task 4: Add fallback and logging coverage

**Files:**
- Modify: `python-ai/src/core/welcome_decision.py`
- Test: `python-ai/tests/test_core/test_welcome_decision.py`

- [ ] **Step 1: Write the failing tests**

```python
@pytest.mark.asyncio
async def test_decide_workflow_welcome_falls_back_to_no_greet_on_invalid_json():
    ...
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest python-ai/tests/test_core/test_welcome_decision.py -v`
Expected: Fail on missing fallback behavior.

- [ ] **Step 3: Write minimal implementation**

```python
try:
    parsed = json.loads(content)
except json.JSONDecodeError:
    logger.warning("welcome.decision.failed ...")
    return {"should_greet": False, "message": "", "reason": "invalid_json"}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest python-ai/tests/test_core/test_welcome_decision.py -v`
Expected: PASS

---