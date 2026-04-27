# Capability Center Refinement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove unused capability center surface area, hide irrelevant schema fields for Skill and MCP, and add real API request testing for API capabilities.

**Architecture:** Keep the change localized to the existing capability center UI and capability service backend. The frontend stops loading and rendering test-record and audit data, conditionally renders schema inputs only for API capabilities, and removes placeholders. The backend keeps non-API validation behavior intact while adding real HTTP execution for API capability tests.

**Tech Stack:** React, TypeScript, Playwright, Spring Boot, JUnit 5, Mockito

---

### Task 1: Frontend Regression Coverage

**Files:**
- Modify: `frontend/tests/e2e/capability-center.spec.ts`
- Test: `frontend/tests/e2e/capability-center.spec.ts`

- [ ] **Step 1: Write failing UI regression tests**

Add Playwright assertions for:
- only the remaining tabs are visible
- schema inputs appear for API and not for Skill/MCP
- capability center inputs and textareas have no `placeholder`

- [ ] **Step 2: Run test to verify it fails**

Run: `npm run test:e2e -- --grep "capability center refinement"`
Expected: FAIL because the page still shows removed tabs, still renders schema for Skill/MCP, or still includes placeholders.

- [ ] **Step 3: Keep the failing expectations as the target behavior**

No production code in this step. Confirm the failures match the intended refinement scope.

- [ ] **Step 4: Re-run targeted Playwright test after each UI change**

Run: `npm run test:e2e -- --grep "capability center refinement"`
Expected: PASS once frontend work is done.

- [ ] **Step 5: Commit**

```bash
git add frontend/tests/e2e/capability-center.spec.ts
git commit -m "test: cover capability center refinement ui"
```

### Task 2: Backend API Real Test Coverage

**Files:**
- Modify: `java-backend/src/test/java/robot/agent/service/CapabilityServiceTest.java`
- Test: `java-backend/src/test/java/robot/agent/service/CapabilityServiceTest.java`

- [ ] **Step 1: Write failing backend tests**

Add JUnit tests for:
- API capability test performs a real HTTP GET and returns success details
- API capability test reports request failures cleanly
- Skill or MCP capability test still behaves as validation-only

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=CapabilityServiceTest test`
Expected: FAIL because `CapabilityService.testCapability(...)` does not yet make a real HTTP request for API capabilities.

- [ ] **Step 3: Keep the failing assertions as the contract**

No production code in this step. Confirm failures are caused by missing API real-request behavior, not by test setup mistakes.

- [ ] **Step 4: Re-run targeted backend test after implementation**

Run: `mvn -Dtest=CapabilityServiceTest test`
Expected: PASS with API real-request behavior and non-API validation preserved.

- [ ] **Step 5: Commit**

```bash
git add java-backend/src/test/java/robot/agent/service/CapabilityServiceTest.java
git commit -m "test: cover capability api real request execution"
```

### Task 3: Frontend Capability Center Cleanup

**Files:**
- Modify: `frontend/src/components/CapabilityCenterPanel.tsx`
- Modify: `frontend/src/services/api.ts`
- Modify: `frontend/src/types/index.ts`
- Test: `frontend/tests/e2e/capability-center.spec.ts`

- [ ] **Step 1: Remove unused frontend capability-center API dependencies**

Delete the unused imports and functions for:
- `getCapabilityTestRecords`
- `getCapabilityAuditRecords`
- `CapabilityTestRecord`
- `CapabilityAuditRecord`

- [ ] **Step 2: Simplify the detail state shape**

Keep only:
- `items`
- `authConfigs`
- `snapshots`

Remove test-record and audit-record state and loading usage from `CapabilityCenterPanel.tsx`.

- [ ] **Step 3: Reduce capability tabs**

Update `CapabilityTab` and `tabOptions` to keep only:
- `items`
- `auth`
- `snapshots`

Delete the rendered tab panels for tests and audit.

- [ ] **Step 4: Remove the test-record summary card**

Keep the summary cards aligned with the remaining visible data.

- [ ] **Step 5: Hide schema fields for non-API capabilities**

Render `inputSchema` and `outputSchema` fields only when `capabilityForm.capabilityType === 'API'`.

- [ ] **Step 6: Adjust payload creation for schema fields**

Ensure `buildCapabilityPayload(...)` only sends schema values for API capabilities, and clears them for Skill/MCP to avoid stale UI state leaking into saves.

- [ ] **Step 7: Differentiate test action copy**

Use API-specific wording for real request testing and non-API wording for validation.

- [ ] **Step 8: Remove all capability-center placeholders**

Delete `placeholder` props from inputs and textareas in:
- group dialog
- capability dialog
- auth dialog

- [ ] **Step 9: Run targeted Playwright test**

Run: `npm run test:e2e -- --grep "capability center refinement"`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add frontend/src/components/CapabilityCenterPanel.tsx frontend/src/services/api.ts frontend/src/types/index.ts frontend/tests/e2e/capability-center.spec.ts
git commit -m "feat: refine capability center ui"
```

### Task 4: Backend API Real Request Execution

**Files:**
- Modify: `java-backend/src/main/java/robot/agent/service/CapabilityService.java`
- Test: `java-backend/src/test/java/robot/agent/service/CapabilityServiceTest.java`

- [ ] **Step 1: Add a minimal HTTP client dependency path inside `CapabilityService`**

Use a simple Spring-friendly HTTP client approach already available in the codebase dependencies, keeping implementation scoped to API test execution.

- [ ] **Step 2: Split `testCapability(...)` by capability type**

For `API`:
- validate required config
- execute the HTTP request
- capture status code, response body, error message, and duration

For `SKILL` / `MCP`:
- preserve the current validation-only logic

- [ ] **Step 3: Persist test results through the existing record flow**

Keep `persistTestRecord(...)` compatibility so existing storage still works.

- [ ] **Step 4: Run targeted backend test**

Run: `mvn -Dtest=CapabilityServiceTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add java-backend/src/main/java/robot/agent/service/CapabilityService.java java-backend/src/test/java/robot/agent/service/CapabilityServiceTest.java
git commit -m "feat: add real api capability test execution"
```

### Task 5: Final Verification

**Files:**
- Modify: none
- Test: `frontend/tests/e2e/capability-center.spec.ts`
- Test: `java-backend/src/test/java/robot/agent/service/CapabilityServiceTest.java`

- [ ] **Step 1: Run frontend targeted verification**

Run: `npm run test:e2e -- --grep "capability center"`
Expected: PASS for capability center coverage.

- [ ] **Step 2: Run backend targeted verification**

Run: `mvn -Dtest=CapabilityServiceTest test`
Expected: PASS

- [ ] **Step 3: Run frontend build verification**

Run: `npm run build`
Expected: PASS

- [ ] **Step 4: Review diff for scope discipline**

Run: `git diff -- frontend/src/components/CapabilityCenterPanel.tsx frontend/src/services/api.ts frontend/src/types/index.ts frontend/tests/e2e/capability-center.spec.ts java-backend/src/main/java/robot/agent/service/CapabilityService.java java-backend/src/test/java/robot/agent/service/CapabilityServiceTest.java`
Expected: Only approved capability center refinement changes.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/CapabilityCenterPanel.tsx frontend/src/services/api.ts frontend/src/types/index.ts frontend/tests/e2e/capability-center.spec.ts java-backend/src/main/java/robot/agent/service/CapabilityService.java java-backend/src/test/java/robot/agent/service/CapabilityServiceTest.java
git commit -m "feat: refine capability center testing and forms"
```
