# Phase 1 Tests (Closed-Loop)

This folder contains Phase 1 test assets aligned to:

- `服务机器人架构设计.md` (source of truth)
- `docs/phase1-contract.md` (frozen cross-service contract; if conflict, architecture wins)

## What to run

Contract smoke tests (will **skip** unless services are reachable):

```powershell
python -m pip install -r tests/phase1/contract_smoke/requirements.txt
pytest -q tests/phase1/contract_smoke
```

Docs:

- `test-matrix.md`: Phase 1 test matrix mapped to acceptance criteria and contract
- `acceptance-checklist.md`: closed-loop acceptance checklist (demo-ready)
- `gaps.md`: current repo test gaps per Java/Python/Frontend

