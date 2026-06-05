# API Center Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the existing Capability Center into a full-stack API Center that removes capability types, API codes, snapshots, and uses Draft-07 body schema validation plus URL template variable mapping.

**Architecture:** Replace backend Capability classes/controllers/repositories with ApiCenter equivalents and expose `/api/api-center/**` endpoints. Update frontend types, services, navigation, and panel UI to API Center semantics. Keep workflow runtime integration compiling by migrating service names and payload keys to API-focused names where practical, while avoiding compatibility with old `API/SKILL/MCP` capability type data.

**Tech Stack:** Spring Boot 3.2, JPA, Jackson, Java 21, React 18, TypeScript, Vite, Playwright.

---

### Task 1: Backend API Center Model

**Files:**
- Create/rename: `java-backend/src/main/java/robot/agent/model/ApiGroup.java`
- Create/rename: `java-backend/src/main/java/robot/agent/model/ApiItem.java`
- Create/rename: `java-backend/src/main/java/robot/agent/model/ApiAuthConfig.java`
- Create/rename: `java-backend/src/main/java/robot/agent/model/ApiTestRecord.java`
- Create/rename: `java-backend/src/main/java/robot/agent/model/ApiAuditRecord.java`
- Delete: `java-backend/src/main/java/robot/agent/model/CapabilityType.java`

- [ ] **Step 1: Rename model classes and tables**

Use `api_group`, `api_item`, `api_auth_config`, `api_test_record`, and `api_audit_record` table names. Remove `capability_type`, `capability_version`, snapshot, and user-facing API code fields from API item/test/audit models.

- [ ] **Step 2: Add URL and body schema fields**

Ensure `ApiItem` contains request URL, method, encrypted header config, input schema, output schema, last test status/time, and last successful test signature.

- [ ] **Step 3: Compile model imports**

Run `mvn -pl java-backend -DskipTests compile` and fix model import errors before proceeding.

### Task 2: Backend Repositories and Services

**Files:**
- Rename: `java-backend/src/main/java/robot/agent/repository/Capability*.java` to `Api*.java`
- Rename/refactor: `java-backend/src/main/java/robot/agent/service/CapabilityService.java` to `ApiCenterService.java`
- Rename/refactor: `java-backend/src/main/java/robot/agent/service/CapabilityAuditService.java` to `ApiAuditService.java`
- Rename/refactor: `java-backend/src/main/java/robot/agent/service/CapabilityRuntimeResolver.java` to `ApiRuntimeResolver.java`

- [ ] **Step 1: Update repository APIs**

Expose lookup by group id and API item id rather than `capabilityCode`. Do not expose or persist `capabilityType`.

- [ ] **Step 2: Add Draft-07 schema validation service logic**

Validate input/output schema JSON syntax, `$schema`, supported `format` values, and reject invalid schemas during save and test.

- [ ] **Step 3: Add URL variable parsing**

Parse `{variableName}` from URL with `[A-Za-z_][A-Za-z0-9_]*`, de-duplicate variables, and require values during request test.

- [ ] **Step 4: Add request test gate**

Require successful request test signature before saving API item. Invalidate signature when URL, method, headers, schemas, URL variables, or body test payload changes.

- [ ] **Step 5: Encrypt header storage**

Persist headers encrypted and decrypt for API item response. Mask headers in test/audit logs.

### Task 3: Backend Controller and Runtime Wiring

**Files:**
- Rename/refactor: `java-backend/src/main/java/robot/agent/controller/CapabilityController.java` to `ApiCenterController.java`
- Modify: `java-backend/src/main/java/robot/agent/service/ExecutionService.java`
- Modify workflow/runtime references as needed.

- [ ] **Step 1: Expose API Center routes**

Use `/api/api-center/groups`, `/api/api-center/groups/{groupId}/items`, `/api/api-center/groups/{groupId}/items/{apiId}`, `/test`, `/auth-configs`, `/tests`, `/audit-records` routes. Do not expose snapshot routes.

- [ ] **Step 2: Remove publish snapshot endpoints**

Delete controller and service snapshot operations.

- [ ] **Step 3: Update execution wiring**

Replace injected Capability services with Api services and keep runtime tool resolution compiling.

### Task 4: Frontend API Center Types and Services

**Files:**
- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/services/api.ts`

- [ ] **Step 1: Rename TypeScript types**

Replace Capability types with Api Center types. Remove `CapabilityType`, `CapabilityGroupSnapshot`, version-oriented snapshot fields, and capability code fields from API Center UI types.

- [ ] **Step 2: Rename service functions and routes**

Use `/api/api-center/**` routes. Remove snapshot functions and capability type payload fields.

### Task 5: Frontend API Center Panel

**Files:**
- Rename/refactor: `frontend/src/components/CapabilityCenterPanel.tsx` to `ApiCenterPanel.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/index.css`

- [ ] **Step 1: Rename navigation**

Change page key and navigation label to `API中心`.

- [ ] **Step 2: Remove capability type and API code UI**

Remove type filter, type selector, Skill/MCP sections, and API code input.

- [ ] **Step 3: Add URL variable mapping UI**

Parse URL placeholders and render variable value inputs for request testing.

- [ ] **Step 4: Add schema validation and save gate UI**

Show Draft-07 schema errors, allow editing on failure, and disable save until schema validation plus request test success.

- [ ] **Step 5: Rename request test copy**

Change `真实请求测试` to `请求测试` and show real request risk warnings.

### Task 6: Tests and Verification

**Files:**
- Modify/add backend tests under `java-backend/src/test/java/robot/agent/service` and `controller`.
- Modify frontend E2E tests under `frontend/tests/e2e`.

- [ ] **Step 1: Add failing tests first**

Add tests for no `capabilityType`, no API code in payloads, URL variable parsing, schema validation, and save gate.

- [ ] **Step 2: Run targeted backend tests**

Run `mvn -pl java-backend test` and fix only failures caused by this refactor.

- [ ] **Step 3: Run frontend build**

Run `npm run build` in `frontend` and fix TypeScript/build errors caused by this refactor.

- [ ] **Step 4: Run text integrity check**

Run `npm run check:text` in `frontend` to ensure Chinese text is not corrupted.