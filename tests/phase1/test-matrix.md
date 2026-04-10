# Phase 1 Test Matrix

Source of truth: `服务机器人架构设计.md` Phase 1.
Frozen contract input: `docs/phase1-contract.md` (if conflict, architecture wins).

## Acceptance Criteria Mapping (Phase 1)

AC-1 User sends message, system can recognize intent.

- Layer: Python (router) + Java (orchestration) + DB (workflow_version.entry_rule)
- Tests:
  - Contract: message request starts an execution and returns `workflow_code/version`
  - Integration: router selects `flight_booking` based on `entry_rule`
  - Negative: unknown intent falls back (Phase 1 can be minimal; must not crash)

AC-2 Enter specific workflow and execute nodes.

- Layer: Python runtime + Java persistence + WebSocket forwarding + Frontend rendering
- Tests:
  - Contract: Python SSE emits `execution.started`, `node.started`, `node.completed`, `execution.completed|suspended|failed`
  - Data: `execution` row created and status updated; `execution_node_log` has per-node records

AC-3 `form` node can suspend and resume.

- Layer: Python runtime (suspend/resume), Java API (form-submit), Frontend (form UI)
- Tests:
  - Contract: SSE emits `form.requested` with `form_definition`
  - Contract: `POST /api/executions/{executionId}/form-submit` makes execution continue
  - Data: node log includes `form` node output and subsequent nodes execute

AC-4 Frontend can see real-time execution process.

- Layer: Java WebSocket push + Frontend WebSocket client + UI
- Tests:
  - Contract: WebSocket emits event envelope with `event_type` and `execution_id`
  - UI: execution timeline updates on `node.started/node.completed`, and message stream on `message_delta`

AC-5 execution logs are fully recorded.

- Layer: Java persistence + schema + runtime event mapping
- Tests:
  - DB: `execution_node_log` includes `node_id`, `node_type`, `status`, `input`, `output`, `metrics`
  - Audit: for a demo run, logs can reconstruct the node chain end-to-end

## Contract Surface (Phase 1)

Frontend -> Java (from `docs/phase1-contract.md` and architecture doc §7.2):

- `POST /api/sessions/{sessionId}/messages`
  - request: `{ message_id, content, attachments }`
  - response: `{ session_id, execution_id, workflow_code, workflow_version, status }`
- `POST /api/executions/{executionId}/form-submit`
  - request: `{ submit_id, form_data }`
  - response: `{ execution_id, status }`
- `GET /api/executions/{executionId}` (contract extension; architecture has execution query concept)

Java -> Frontend WebSocket (architecture doc §7.3):

- Path not mandated by architecture; contract suggests `/ws/executions`
- Event envelope:
  - `{ type: "event", event_type: "...", execution_id, data }`
  - `{ type: "message_delta", execution_id, content, is_complete }`
- Must support: `execution.*`, `node.*`, `form.requested`, `message.delta`

Java -> Python (architecture doc §7.1):

- `POST /api/execute` with `Accept: text/event-stream`
- request: `{ session_id, execution_id, workflow_code, workflow_version, input_variables }`
- response: SSE stream events

## Known Contract vs Architecture Conflicts (must resolve)

1. `execution.status` casing:
   - Target (latest): lower-case values as specified by `docs/phase1-contract.md` (`pending|running|suspended|completed|failed|cancelled`).
   - Current repo migration uses upper-case enums in `java-backend/src/main/resources/db/migration/V1__create_tables.sql` and must be updated to match the target.
   - Tests and assertions should follow the target lower-case; treat upper-case as a temporary deviation to be fixed.
