# Phase 1 Closed-Loop Acceptance Checklist

Aligned to:
- `服务机器人架构设计.md` Phase 1 acceptance criteria
- `docs/phase1-contract.md`

## Preconditions

- MySQL is running and schema migrated.
- Java service is running (gateway + persistence + WebSocket push).
- Python runtime is running (FastAPI + router + scheduler + SSE events).
- Frontend is running and configured to talk to Java (HTTP + WebSocket).

## Demo Scenario (recommended: `flight_booking`)

1. Prepare a published workflow version:
   - `workflow_code`: `flight_booking`
   - `workflow_version.entry_rule` contains `intent_codes` and/or `keywords` as in architecture doc example.
   - Nodes at least: `start` -> `llm` -> `condition` -> `form` -> `end`.

2. Send a message missing required slots:
   - Call `POST /api/sessions/{sessionId}/messages` with content like: `我要订机票`
   - Expect:
     - Response contains `execution_id` and `workflow_code/version`
     - Frontend shows execution started and node events via WebSocket

3. Verify `form` suspend:
   - Expect:
     - WebSocket emits `form.requested` for `execution_id`
     - UI displays form fields
     - Backend records `execution.status` as suspended/waiting_user (implementation choice) but must be consistent end-to-end

4. Submit form:
   - Call `POST /api/executions/{executionId}/form-submit` with required fields
   - Expect:
     - Execution resumes
     - Node events continue and `end` produces final message
     - UI shows final assistant message

5. Verify persistence:
   - `GET /api/executions/{executionId}` returns correct status and `current_node_id`
   - DB has:
     - `execution` row for `execution_id`
     - `execution_node_log` rows for each node including `form`

## Pass/Fail Criteria

Pass only if all are true:

- Intent routing selects the expected workflow (`workflow_code/version` not empty and matches rule).
- Nodes execute in sequence, visible in UI in real-time.
- `form` causes suspend and can resume via form-submit.
- Final assistant output is shown.
- `execution` and `execution_node_log` are complete enough to reconstruct what happened.

