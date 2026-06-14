# Knowledge Center Verification

Date: 2026-06-14

## Commands

- `mvn -pl java-backend test`
- `cd python-ai && pytest -q`
- `cd frontend && npm run build`
- `cd frontend && $env:PLAYWRIGHT_PORT='5174'; npm run test:e2e -- knowledge-center.spec.ts`
- `docker compose config`
- `cd frontend && npm run check:text`
- `rg -n "ms-[0-9a-fA-F-]{36}|5eba410e|acw_tc|Cookie:" .`
- `rg -n "\?\?\?\?|�" frontend\src frontend\tests\e2e frontend\playwright.config.ts python-ai\src\core\__init__.py`

## Result

- Java backend: 95 tests passed.
- Python AI: 146 tests passed, 4 deprecation warnings from FastAPI lifespan APIs.
- Frontend build: completed successfully.
- Knowledge center E2E: 1 test passed on port 5174 to avoid an existing 5173 dev server from another worktree.
- Docker Compose config: completed successfully and includes `mysql`, `redis`, `pgvector`, and `minio`.
- Text integrity and sensitive token scans: no matches.
