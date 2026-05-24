# Intent Recognition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the serial intent-recognition flow from `docs/superpowers/specs/2026-05-07-intent-recognition-design.md`: regex first, RAG-like local recall second, Python LLM fallback last, with candidate queue confirmation and no duplicate routing stacks.

**Architecture:** Java remains the routing orchestrator and owns workflow selection, thresholds, candidate queues, and chat responses. Python only performs structured LLM fallback for Top-K candidates. Frontend only exposes existing workflow entry-rule configuration and candidate confirmation UX.

**Tech Stack:** Spring Boot 3 / Java 21, FastAPI / Pydantic / pytest, React 18 / TypeScript / Vite.

---

## Constraints

- Do not add a second routing subsystem. Reuse `WorkflowService.routeMessage`, `PythonClient.classifyIntent`, and existing chat APIs.
- Do not add database tables. Store transient candidate queue state in `Session.variables` under `intent_candidate_queue`.
- Do not implement unrelated enhancements from the spec's “暂不需要补强” scope.
- Keep all changed files UTF-8 without BOM.
- Do not commit changes unless the user explicitly asks.

## Cross-Tier Contract

### Intent route result fields

Java returns existing `SendMessageResponse` fields plus minimal additions:

- `route_decision`: `start`, `fallback`, `clarification_required`, or `candidate_confirmation_required`.
- `route_reason`: `regex_match`, `rag_match`, `llm_match`, `llm_no_match`, `candidate_confirmation`, or existing governance reason.
- `candidate_workflows`: workflow codes only, for backward compatibility.
- `intent_candidate_queue`: structured queued candidates for frontend display.
- `clarification_question`: question to show when no intent matches.

### Python fallback response

Python `/api/phase5/intents/classify` returns:

```json
{
  "matched": true,
  "intent_code": "intent_order_query",
  "workflow_code": "order_query",
  "target_type": "workflow",
  "target_code": "order_query",
  "confidence": 0.76,
  "reason": "用户表达了查询订单状态的需求",
  "need_clarification": false,
  "clarification_question": null
}
```

If no match is reliable, Python returns `matched=false` with `need_clarification=true` and a question.

---

## Task 1: Java Routing Core

**Owner:** Java agent  
**Files:**
- Modify: `java-backend/src/main/java/robot/agent/service/RoutingDecision.java`
- Modify: `java-backend/src/main/java/robot/agent/service/WorkflowService.java`
- Modify: `java-backend/src/main/java/robot/agent/service/PythonClient.java` only if logging needs new request fields
- Test: `java-backend/src/test/java/robot/agent/service/WorkflowServiceTest.java`

- [ ] **Step 1: Add route metadata to `RoutingDecision`**

Add optional fields without removing existing constructor compatibility by updating call sites once:

```java
String intentCode,
String targetType,
String targetCode,
String clarificationQuestion,
List<IntentCandidate> intentCandidateQueue
```

Add nested record:

```java
public record IntentCandidate(
        String intentCode,
        String targetType,
        String targetCode,
        double confidence,
        String source,
        String evidence
) {}
```

- [ ] **Step 2: Split routing into serial phases**

In `WorkflowService.routeMessage`, preserve current visible workflow resolution, then call:

```java
List<IntentCandidate> regexCandidates = collectRegexCandidates(versions, normalizedContent);
if (!regexCandidates.isEmpty()) {
    return buildAcceptedRoutingDecision(regexCandidates, versions, "regex_match");
}

List<IntentCandidate> ragCandidates = collectRagCandidates(versions, normalizedContent);
if (!ragCandidates.isEmpty() && ragCandidates.get(0).confidence() >= ragAcceptThreshold()) {
    return buildAcceptedRoutingDecision(ragCandidates, versions, "rag_match");
}

ModelIntent modelIntent = classifyIntent(normalizedContent, versions, routingModelCode, runtimeBundle, ragCandidates);
return buildLlmRoutingDecision(modelIntent, ragCandidates, versions, normalizedContent);
```

Do not call `classifyIntent` before regex/RAG phases.

- [ ] **Step 3: Implement regex candidates from existing entry rules**

Use existing `entry_rule` JSON. Support `regex_patterns` when present, and keep existing `keywords` / `intent_codes` as deterministic contains matches.

Candidate source must be `regex`, confidence should be `1.0`, target type should be `workflow`, target code should be `version.getWorkflowCode()`.

- [ ] **Step 4: Implement lightweight RAG-like recall without new dependencies**

Use local token overlap scoring against workflow name/description, `entry_rule.intent_codes`, `entry_rule.keywords`, and optional `entry_rule.examples`.

Normalize score into `[0, 1]`, sort descending, and keep Top-K. This is the current implementation's RAG substitute and must not call Python or LLM.

- [ ] **Step 5: Limit LLM input to Top-K candidates**

Pass only RAG Top-K candidates into the existing Python classify endpoint as `candidate_workflows`. Include `target_type`, `target_code`, `confidence`, and `evidence` in each candidate map.

- [ ] **Step 6: Add no-match route**

If Python returns `matched=false`, return a `RoutingDecision` with `decision=clarification_required`, no executable workflow switch, `reason=llm_no_match`, and `clarificationQuestion` from Python or default text.

- [ ] **Step 7: Add tests**

Add tests proving:

1. Regex match does not call `pythonClient.classifyIntent`.
2. RAG match above threshold does not call `pythonClient.classifyIntent`.
3. RAG below threshold calls Python with only Top-K candidates.
4. Python no-match returns `clarification_required`.

Run:

```powershell
mvn -pl java-backend -Dtest=WorkflowServiceTest test
```

Expected: targeted tests pass.

---

## Task 2: Java Candidate Queue and Chat Response

**Owner:** Java agent  
**Files:**
- Modify: `java-backend/src/main/java/robot/agent/dto/request/SendMessageRequest.java`
- Modify: `java-backend/src/main/java/robot/agent/dto/response/SendMessageResponse.java`
- Modify: `java-backend/src/main/java/robot/agent/service/ExecutionService.java`
- Test: `java-backend/src/test/java/robot/agent/service/ExecutionServiceTest.java`

- [ ] **Step 1: Add request fields for candidate confirmation**

Add:

```java
@JsonProperty("intent_candidate_action")
private String intentCandidateAction;
@JsonProperty("intent_candidate_target_code")
private String intentCandidateTargetCode;
```

Accepted actions: `accept`, `reject`, or null.

- [ ] **Step 2: Add response fields**

Add:

```java
@JsonProperty("intent_candidate_queue")
private List<RoutingDecision.IntentCandidate> intentCandidateQueue;
@JsonProperty("clarification_question")
private String clarificationQuestion;
```

- [ ] **Step 3: Persist queue in `Session.variables`**

After starting the primary intent, write remaining candidates from `routingDecision.intentCandidateQueue()` into `Session.variables.intent_candidate_queue`.

Do not write the primary candidate into the queue.

- [ ] **Step 4: Handle candidate confirmation before fresh routing**

At the start of `startExecution`, if `intent_candidate_action=accept`, pop the matching queued candidate and start its workflow explicitly. If `reject`, remove it and return the next queued candidate as `candidate_confirmation_required` without starting a workflow.

- [ ] **Step 5: Return clarification without starting execution**

If `routingDecision.decision()` is `clarification_required`, return a `SendMessageResponse` with `execution_id=null`, `status=clarification_required`, and `clarification_question` populated.

- [ ] **Step 6: Add tests**

Add tests proving:

1. Queue candidates are stored after primary route starts.
2. `accept` starts the queued workflow.
3. `reject` removes one candidate and returns the next confirmation if present.
4. `clarification_required` returns no execution id.

Run:

```powershell
mvn -pl java-backend -Dtest=ExecutionServiceTest test
```

Expected: targeted tests pass.

---

## Task 3: Python LLM Fallback Contract

**Owner:** Python agent  
**Files:**
- Modify: `python-ai/src/api/models.py`
- Modify: `python-ai/src/core/model_runtime.py`
- Test: `python-ai/tests/test_core/test_model_runtime.py`

- [ ] **Step 1: Extend request model**

Allow each `candidate_workflows` item to carry `target_type`, `target_code`, `confidence`, and `evidence`. Keep it as `list[Dict[str, Any]]` to avoid redundant model classes.

- [ ] **Step 2: Update prompt contract**

In `classify_intent_with_model_code`, require JSON fields:

```python
["matched", "intent_code", "workflow_code", "target_type", "target_code", "confidence", "reason", "need_clarification", "clarification_question"]
```

Tell the model to choose only from provided candidates or return `matched=false`.

- [ ] **Step 3: Validate parsed output**

If `matched=true`, require `workflow_code` and clamp confidence into `[0.0, 1.0]`. If `matched=false`, normalize null route fields and ensure `need_clarification=true` with a default Chinese clarification question.

- [ ] **Step 4: Add tests**

Add tests proving:

1. Matched JSON includes new structured fields.
2. No-match JSON is normalized with `need_clarification=true`.
3. Invalid JSON still raises `ModelExecutionError`.

Run:

```powershell
cd python-ai; python -m pytest tests/test_core/test_model_runtime.py -q
```

Expected: targeted tests pass.

---

## Task 4: Frontend Entry Rules and Confirmation UX

**Owner:** Frontend agent  
**Files:**
- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/services/api.ts`
- Modify: `frontend/src/components/Orchestrator.tsx`
- Modify: `frontend/src/components/MessageList.tsx` only if rendering structured candidate messages is simpler there

- [ ] **Step 1: Extend frontend types**

Add `IntentCandidate`, `intent_candidate_queue`, `clarification_question`, `intentCandidateAction`, and `intentCandidateTargetCode` types matching Java JSON.

- [ ] **Step 2: Extend `sendMessage` options**

Add request body fields:

```ts
intent_candidate_action: options?.intentCandidateAction ?? null,
intent_candidate_target_code: options?.intentCandidateTargetCode ?? null,
```

- [ ] **Step 3: Add entry-rule editor using existing workflow info panel**

Do not create a new page. Add fields in `renderWorkflowInfoPanel` for:

- intent codes, comma-separated
- keywords, comma-separated
- regex patterns, one per line
- examples, one per line
- priority number

Persist these values through existing `currentEntryRule`, draft save, and publish paths.

- [ ] **Step 4: Show clarification message**

When send response status is `clarification_required`, add an AI/system message containing `clarification_question` and do not attach an execution id.

- [ ] **Step 5: Show candidate confirmation actions**

When response has `intent_candidate_queue`, display the next candidate with two buttons: “继续办理” and “跳过”. Buttons call `sendMessage` with `intentCandidateAction=accept/reject` and the candidate target code.

- [ ] **Step 6: Verify build**

Run:

```powershell
cd frontend; npm run build
```

Expected: TypeScript and Vite build pass.

---

## Final Integration

- [ ] Run Java targeted tests.
- [ ] Run Python targeted tests.
- [ ] Run frontend build.
- [ ] Run `git diff --check`.
- [ ] Confirm no duplicate router, duplicate Python RAG service, or duplicate frontend configuration page was added.
