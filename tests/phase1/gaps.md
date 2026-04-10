# Phase 1 Testing Gaps (Current Repo)

This is an objective gap list against:
- `服务机器人架构设计.md` Phase 1 acceptance criteria
- `docs/phase1-contract.md`

## Java (java-backend/**)

- No runnable test suite is wired up under `java-backend/src/test/**` (folder appears missing/misplaced).
- Contract endpoints required by Phase 1 are not implemented in Java yet:
  - Current `ChatController` is `/api/chat/*` and returns a stub response.
  - Contract requires `/api/sessions/{sessionId}/messages` and `/api/executions/{executionId}/form-submit`.
- DTO request classes referenced in code (e.g. `CreateSessionRequest`) are missing in `java-backend/src/main/java/.../dto/request`.
- DB migration exists but does not include `execution_node_log` table (required by architecture Phase 1).
- Status casing mismatch in DB enums:
  - Target per `docs/phase1-contract.md`: lower-case (`pending|running|suspended|completed|failed|cancelled`).
  - Current `java-backend/src/main/resources/db/migration/V1__create_tables.sql` uses upper-case enums and must be updated.

## Python (python-ai/**)

- Existing tests cover:
  - State machine transitions (unit)
  - Condition node (unit)
  - Tool node (unit)
- Missing for Phase 1:
  - SSE event stream contract: `execution.*`, `node.*`, `form.requested`, `message.delta`.
  - `form` suspend + resume semantics (currently `FormNode` only returns a form payload; no resume API).
  - `/api/execute` endpoint (current FastAPI uses `/execution/start`).
- Test infra gap:
  - `python-ai/requirements.txt` does not include `pytest` so tests are not runnable from a clean env.
- Python 目录口径:
  - 已统一为 `python-ai/**`（以最新主线口径为准）。

## Frontend (frontend/**)

- Existing e2e spec uses Playwright but `frontend/package.json` has no Playwright dependency or test script, so it is not runnable.
- UI currently calls `/api/chat/message` (stub Java endpoint); contract requires `/api/sessions/{sessionId}/messages`.
- Missing:
  - WebSocket client consumption and timeline rendering of node events
  - form UI bound to `form.requested` and form-submit POST
  - Orchestrator (React Flow) is not present in dependencies and UI

## Cross-service (Contract)

- Contract is present in `docs/phase1-contract.md` but not enforced by automated tests yet.
- Need a contract smoke suite that can:
  - Validate HTTP shapes
  - Validate SSE event names and minimal payload keys
  - Validate WebSocket envelope shapes
