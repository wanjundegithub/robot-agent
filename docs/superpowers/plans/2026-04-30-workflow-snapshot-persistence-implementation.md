# Workflow Snapshot Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist and reload complete workflow versions as database JSON snapshots so nodes, edges, variables, layouts, and workflow metadata are not lost after save/publish/reopen.

**Architecture:** Add a `workflow_snapshot` JSON column to `workflow_version` while preserving existing `definition`, `entry_rule`, `editor_meta`, and `config` fields for compatibility. Frontend creates one canonical `workflow-snapshot/v1` object and derives legacy fields from the same data; backend validates/normalizes and returns the snapshot on version reads.

**Tech Stack:** Spring Boot/JPA/JdbcTemplate/JUnit/Mockito backend, React/TypeScript/ReactFlow frontend, MySQL-compatible JSON storage.

---

## File Structure

- Modify: `java-backend/src/main/java/robot/agent/model/WorkflowVersion.java` — add `workflowSnapshot` JPA field.
- Modify: `java-backend/src/main/java/robot/agent/dto/request/CreateWorkflowVersionRequest.java` — accept `workflow_snapshot`.
- Modify: `java-backend/src/main/java/robot/agent/dto/response/WorkflowVersionResponse.java` — return `workflowSnapshot`.
- Modify: `java-backend/src/main/java/robot/agent/service/WorkflowSchemaRepairService.java` — add idempotent column repair.
- Modify: `java-backend/src/main/java/robot/agent/service/WorkflowService.java` — normalize, persist, backfill, and publish snapshots.
- Modify: `java-backend/src/test/java/robot/agent/service/WorkflowServiceTest.java` — verify save/publish snapshot behavior.
- Modify: `frontend/src/types/index.ts` — add workflow snapshot types/field.
- Modify: `frontend/src/services/api.ts` — send `workflow_snapshot` in save draft requests.
- Modify: `frontend/src/components/Orchestrator.tsx` — build snapshots and hydrate from snapshots first.

## Coordination Rules

- Backend worker owns only `java-backend/**` files.
- Frontend worker owns only `frontend/src/**` files.
- Workers are not alone in the codebase: do not revert edits outside your ownership; adapt to existing changes.
- Preserve UTF-8 without BOM. Do not convert encodings.
- Do not commit changes.

### Task 1: Backend Snapshot Persistence

**Files:**
- Modify: `java-backend/src/main/java/robot/agent/model/WorkflowVersion.java`
- Modify: `java-backend/src/main/java/robot/agent/dto/request/CreateWorkflowVersionRequest.java`
- Modify: `java-backend/src/main/java/robot/agent/dto/response/WorkflowVersionResponse.java`
- Modify: `java-backend/src/main/java/robot/agent/service/WorkflowSchemaRepairService.java`
- Modify: `java-backend/src/main/java/robot/agent/service/WorkflowService.java`
- Test: `java-backend/src/test/java/robot/agent/service/WorkflowServiceTest.java`

- [ ] **Step 1: Add backend failing tests**

Add tests proving `saveWorkflowDraft()` persists a supplied snapshot, and `publishWorkflow()` backfills a snapshot when publishing an old version that only has legacy JSON fields. Use existing Mockito style in `WorkflowServiceTest`.

- [ ] **Step 2: Run backend targeted tests**

Run: `mvn -pl java-backend -Dtest=WorkflowServiceTest test`

Expected: new tests fail because `workflowSnapshot` does not exist yet.

- [ ] **Step 3: Add DTO and entity fields**

Add `workflowSnapshot` field with `@JsonProperty("workflow_snapshot")` on request and response DTOs, plus JPA column `@Column(name = "workflow_snapshot", columnDefinition = "JSON")` on `WorkflowVersion`.

- [ ] **Step 4: Add schema repair**

Add `ensureWorkflowSnapshotColumnSupported()` to `WorkflowSchemaRepairService`. It should query `SHOW COLUMNS FROM workflow_version LIKE 'workflow_snapshot'` and run `ALTER TABLE workflow_version ADD COLUMN workflow_snapshot JSON NULL` only when absent.

- [ ] **Step 5: Normalize and persist snapshots**

In `WorkflowService.saveWorkflowDraft()`, normalize `request.getDefinition()` as today, persist legacy fields, then persist `workflowSnapshot`. If request snapshot is blank, create a compatibility snapshot from normalized definition, entry rule, editor meta, config, workflow code/name/version. If request snapshot is present, parse it strictly, enforce `schema_version = workflow-snapshot/v1`, normalize `designer.definition`, and rewrite workflow metadata.

- [ ] **Step 6: Backfill on publish**

In `publishWorkflow()`, before setting status to `PUBLISHED`, ensure schema column exists and set `workflowSnapshot` if blank by building a compatibility snapshot from the version's current fields.

- [ ] **Step 7: Return snapshots**

Ensure `WorkflowVersionResponse.fromEntity()` copies `workflowSnapshot`.

- [ ] **Step 8: Verify backend**

Run: `mvn -pl java-backend -Dtest=WorkflowServiceTest test`

Expected: tests pass.

### Task 2: Frontend Snapshot Save and Hydrate

**Files:**
- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/services/api.ts`
- Modify: `frontend/src/components/Orchestrator.tsx`

- [ ] **Step 1: Add frontend types**

Add `WorkflowSnapshotV1` type and `workflowSnapshot?: string` to `WorkflowVersionSummary`.

- [ ] **Step 2: Update API save payload**

Extend `saveWorkflowDraft()` payload with `workflowSnapshot: Record<string, unknown>` and include `workflow_snapshot: JSON.stringify(payload.workflowSnapshot)` in the request body.

- [ ] **Step 3: Build canonical snapshot**

Add `buildWorkflowSnapshot()` near existing workflow serialization helpers. It should return `workflow-snapshot/v1` with `workflow` and `designer` sections, using the same `definition`, `entryRule`, `workflowConfig`, and `definition.editor_meta` that are already saved.

- [ ] **Step 4: Save snapshot for draft and publish**

In `persistDraft(version)`, build the snapshot from the version-adjusted `definition` and pass it to `saveWorkflowDraft()`. Keep `publish` flow order unchanged: validate, persist publish version, then publish.

- [ ] **Step 5: Hydrate from snapshot first**

In `hydrateWorkflowSelection()`, parse `selection.version.workflowSnapshot` first. If it has `schema_version = workflow-snapshot/v1` and a valid `designer.definition`, use snapshot `designer.definition`, `designer.editor_meta`, and `designer.workflow_config`; otherwise fall back to current legacy fields.

- [ ] **Step 6: Verify frontend build**

Run from `frontend`: `npm run build`

Expected: TypeScript and Vite build pass.

### Task 3: Integration Verification

**Files:**
- Inspect only unless issues are found.

- [ ] **Step 1: Review diff boundaries**

Run: `git diff --stat` and verify backend worker only changed `java-backend/**`, frontend worker only changed `frontend/src/**`, plus docs already created by controller.

- [ ] **Step 2: Run backend tests**

Run: `mvn -pl java-backend -Dtest=WorkflowServiceTest test`

Expected: pass.

- [ ] **Step 3: Run frontend build**

Run from `frontend`: `npm run build`

Expected: pass.

- [ ] **Step 4: Static persistence sanity check**

Inspect code paths to confirm save draft request sends `workflow_snapshot`, backend response returns `workflowSnapshot`, and hydrate reads snapshot before legacy fields.

- [ ] **Step 5: Summarize manual联调 path**

If app runtime is not launched in this session, provide manual verification steps: create/edit workflow, add variable, add edge, save draft, publish, reopen version, confirm data survives.
