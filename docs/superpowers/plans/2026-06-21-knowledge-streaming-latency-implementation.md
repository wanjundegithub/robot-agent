# Knowledge Streaming Latency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stream chat knowledge-hit answers through the existing WebSocket `message.delta` channel and reduce vector retrieval cost by making the default pgvector embedding dimension 1024.

**Architecture:** Keep knowledge hits out of the full workflow scheduler, but give each knowledge answer a lightweight `knowledge_<uuid>` id and publish answer chunks through `UserConnectionManager`. Keep routing accuracy by preserving the current knowledge threshold decision and soft-delete filtering. Change Python vector dimension defaults and embedding option handling so the pgvector table and upstream embedding request can consistently use 1024 dimensions after reindexing.

**Tech Stack:** Spring Boot 3.2, JUnit 5, Mockito, FastAPI, pytest, pgvector, WebSocket frame protocol.

---

## File Map

- Modify `java-backend/src/main/java/robot/agent/service/ExecutionService.java`: return `knowledge_answer_streaming`, create lightweight knowledge ids, stream answer chunks through `UserConnectionManager`.
- Modify `java-backend/src/main/java/robot/agent/service/WorkflowService.java`: set `generateAnswer=false` for chat knowledge routing.
- Modify `java-backend/src/test/java/robot/agent/service/ExecutionServiceFallbackTest.java`: verify knowledge answer streaming behavior.
- Modify `java-backend/src/test/java/robot/agent/service/WorkflowKnowledgeRouteServiceTest.java`: verify chat knowledge route disables answer generation.
- Modify `python-ai/src/core/settings.py`: change default `vector_dimension` from 4096 to 1024 while keeping the default table as `knowledge_chunks`.
- Modify `python-ai/src/core/knowledge_store.py`: rebuild `knowledge_chunks` automatically when the existing embedding dimension differs from the configured dimension.
- Modify `python-ai/src/core/embedding_runtime.py`: accept `embedding_dimension` as an alias for `dimensions` and send `dimensions` to the provider.
- Modify Python tests under `python-ai/tests/test_api` and `python-ai/tests/test_core`: update expected dimensions and add alias coverage.

## Task 1: Java Knowledge Hit Streaming

**Files:**
- Modify: `java-backend/src/test/java/robot/agent/service/ExecutionServiceFallbackTest.java`
- Modify: `java-backend/src/main/java/robot/agent/service/ExecutionService.java`

- [ ] **Step 1: Write the failing streaming test**

Add this test to `ExecutionServiceFallbackTest`:

```java
@Test
void startExecutionStreamsKnowledgeAnswerOverMessageDelta() {
    Session session = new Session();
    session.setId("session-1");
    session.setWorkspaceId(1L);
    session.setUserId("session-user");

    SendMessageRequest request = new SendMessageRequest();
    request.setUserId("request-user");
    request.setContent("保修期多久");

    RoutingDecision routingDecision = new RoutingDecision(
            "knowledge_answer",
            null,
            null,
            0.92d,
            0.65d,
            "knowledge_primary_threshold",
            "knowledge_primary",
            List.of(),
            0,
            null,
            "knowledge",
            null,
            "保修期为一年。",
            List.of()
    );

    when(sessionService.getOrCreateSession("session-1", "request-user")).thenReturn(session);
    when(workflowService.routeMessage("保修期多久", null, "session-1", "request-user")).thenReturn(routingDecision);

    SendMessageResponse response = executionService.startExecution("session-1", request);

    assertThat(response.getSessionId()).isEqualTo("session-1");
    assertThat(response.getExecutionId()).startsWith("knowledge_");
    assertThat(response.getStatus()).isEqualTo("knowledge_answer_streaming");
    assertThat(response.getRouteDecision()).isEqualTo("knowledge_answer");
    assertThat(response.getClarificationQuestion()).isEqualTo("保修期为一年。");
    verify(userConnectionManager).sendMessageDeltaFrame(
            argThat(value -> value != null && value.startsWith("knowledge_")),
            eq("session-1"),
            eq(""),
            eq(false)
    );
    verify(userConnectionManager).sendMessageDeltaFrame(
            argThat(value -> value != null && value.startsWith("knowledge_")),
            eq("session-1"),
            eq("保修期为一年。"),
            eq(false)
    );
    verify(userConnectionManager).sendMessageDeltaFrame(
            argThat(value -> value != null && value.startsWith("knowledge_")),
            eq("session-1"),
            eq(""),
            eq(true)
    );
    verify(executionRepository, never()).save(any(Execution.class));
}
```

Add missing static imports if needed:

```java
import static org.mockito.ArgumentMatchers.eq;
```

- [ ] **Step 2: Run the Java test and verify it fails**

Run:

```bash
mvn -pl java-backend -Dtest=ExecutionServiceFallbackTest#startExecutionStreamsKnowledgeAnswerOverMessageDelta test
```

Expected: FAIL because `executionId` is null, `status` is still `clarification_required`, and no `message.delta` frames are sent.

- [ ] **Step 3: Implement minimal streaming behavior**

In `ExecutionService.java`, update `buildKnowledgeAnswerResponse` so it creates a lightweight id, streams the answer, and returns the streaming status:

```java
private SendMessageResponse buildKnowledgeAnswerResponse(
        Session session,
        Execution activeExecution,
        RoutingDecision routingDecision
) {
    SendMessageResponse response = buildRouteDecisionResponse(session, activeExecution, routingDecision);
    String knowledgeExecutionId = "knowledge_" + UUID.randomUUID().toString().replace("-", "");
    String answer = routingDecision.clarificationQuestion();
    response.setExecutionId(knowledgeExecutionId);
    response.setStatus("knowledge_answer_streaming");
    response.setRouteDecision("knowledge_answer");
    streamKnowledgeAnswer(knowledgeExecutionId, session.getId(), answer);
    return response;
}

private void streamKnowledgeAnswer(String executionId, String sessionId, String answer) {
    sendMessageDeltaFrame(executionId, sessionId, "", false);
    for (String chunk : splitKnowledgeAnswer(answer)) {
        sendMessageDeltaFrame(executionId, sessionId, chunk, false);
    }
    sendMessageDeltaFrame(executionId, sessionId, "", true);
}

private List<String> splitKnowledgeAnswer(String answer) {
    if (answer == null || answer.isBlank()) {
        return List.of();
    }
    String normalized = answer.trim();
    if (normalized.length() <= 120) {
        return List.of(normalized);
    }
    List<String> chunks = new ArrayList<>();
    int offset = 0;
    while (offset < normalized.length()) {
        int end = Math.min(offset + 120, normalized.length());
        chunks.add(normalized.substring(offset, end));
        offset = end;
    }
    return chunks;
}
```

- [ ] **Step 4: Run the Java test and verify it passes**

Run:

```bash
mvn -pl java-backend -Dtest=ExecutionServiceFallbackTest#startExecutionStreamsKnowledgeAnswerOverMessageDelta test
```

Expected: PASS.

## Task 2: Java Chat Route Disables Knowledge Answer Generation

**Files:**
- Modify: `java-backend/src/test/java/robot/agent/service/WorkflowKnowledgeRouteServiceTest.java`
- Modify: `java-backend/src/main/java/robot/agent/service/WorkflowService.java`

- [ ] **Step 1: Write the failing request-capture assertion**

In the knowledge-answer test in `WorkflowKnowledgeRouteServiceTest`, capture the `KnowledgeSearchRequest` passed to `knowledgeService.searchKnowledge` and assert:

```java
ArgumentCaptor<KnowledgeSearchRequest> requestCaptor = ArgumentCaptor.forClass(KnowledgeSearchRequest.class);
verify(knowledgeService).searchKnowledge(eq("request-user"), requestCaptor.capture());
assertThat(requestCaptor.getValue().getGenerateAnswer()).isFalse();
```

If no existing knowledge-answer test has user id `request-user`, use the user id already passed to `routeMessage`.

- [ ] **Step 2: Run the route test and verify it fails**

Run:

```bash
mvn -pl java-backend -Dtest=WorkflowKnowledgeRouteServiceTest test
```

Expected: FAIL because `WorkflowService` currently sets `generateAnswer=true`.

- [ ] **Step 3: Change chat knowledge route request**

In `WorkflowService.tryBuildKnowledgeRoutingDecision`, change:

```java
request.setGenerateAnswer(true);
```

to:

```java
request.setGenerateAnswer(false);
```

- [ ] **Step 4: Run the route test and verify it passes**

Run:

```bash
mvn -pl java-backend -Dtest=WorkflowKnowledgeRouteServiceTest test
```

Expected: PASS.

## Task 3: Python Vector Dimension Defaults and Embedding Alias

**Files:**
- Modify: `python-ai/tests/test_core/test_embedding_runtime.py`
- Modify: `python-ai/tests/test_core/test_knowledge_store_sql.py`
- Modify: `python-ai/tests/test_core/test_knowledge_ingestion.py`
- Modify: `python-ai/tests/test_api/test_knowledge_search.py`
- Modify: `python-ai/src/core/settings.py`
- Modify: `python-ai/src/core/embedding_runtime.py`

- [ ] **Step 1: Write failing Python expectations**

Change tests that expect 4096 to expect 1024:

```python
assert captured["embedding"] == [0.2] * 1024
```

```python
assert kwargs["expected_dimension"] == 1024
```

```python
assert "embedding VECTOR(1024) NOT NULL" in sql
```

Add this test to `test_embedding_runtime.py`:

```python
@pytest.mark.asyncio
async def test_embed_texts_uses_embedding_dimension_alias_as_dimensions():
    provider_configs = {
        "embedding-provider": {
            "provider_code": "embedding-provider",
            "provider_type": "openai_compatible",
            "base_url": "https://embedding.example.com/v1",
            "api_key_secret_ref": "",
            "extra_headers": {"__meta__": {"embedding_path": "/embeddings"}},
        }
    }
    model_records = {
        "embedding-small": {
            "model_code": "embedding-small",
            "provider_code": "embedding-provider",
            "upstream_model_code": "embedding-small-upstream",
            "default_options": {"embedding_dimension": 1024, "timeout_sec": 10},
        }
    }
    with patch("src.core.embedding_runtime.httpx.AsyncClient") as mock_client:
        response = AsyncMock()
        response.raise_for_status = lambda: None
        response.json.return_value = {"data": [{"embedding": [0.1] * 1024}]}
        instance = mock_client.return_value.__aenter__.return_value
        instance.post = AsyncMock(return_value=response)

        vectors = await embed_texts_with_model(
            texts=["保修政策"],
            model_code="embedding-small",
            provider_configs=provider_configs,
            model_records=model_records,
            expected_dimension=1024,
        )

    assert len(vectors[0]) == 1024
    body = instance.post.call_args.kwargs["json"]
    assert body["dimensions"] == 1024
```

- [ ] **Step 2: Run Python tests and verify they fail**

Run:

```bash
cd python-ai
pytest tests/test_core/test_embedding_runtime.py tests/test_core/test_knowledge_store_sql.py tests/test_core/test_knowledge_ingestion.py tests/test_api/test_knowledge_search.py -q
```

Expected: FAIL because defaults remain 4096 and `embedding_dimension` is not sent as `dimensions`.

- [ ] **Step 3: Implement Python dimension changes**

In `python-ai/src/core/settings.py`, change:

```python
vector_dimension: int = 4096
```

to:

```python
vector_dimension: int = 1024
```

Keep `vector_table` as `knowledge_chunks` and rely on startup schema rebuild when an old 4096-dimensional table is present.

In `python-ai/src/core/embedding_runtime.py`, replace:

```python
if options.get("dimensions"):
    body["dimensions"] = int(options["dimensions"])
```

with:

```python
dimensions = options.get("dimensions", options.get("embedding_dimension"))
if dimensions:
    body["dimensions"] = int(dimensions)
```

- [ ] **Step 4: Run Python tests and verify they pass**

Run:

```bash
cd python-ai
pytest tests/test_core/test_embedding_runtime.py tests/test_core/test_knowledge_store_sql.py tests/test_core/test_knowledge_ingestion.py tests/test_api/test_knowledge_search.py -q
```

Expected: PASS.

## Task 4: Focused Regression Verification

**Files:**
- No source edits unless tests reveal a regression.

- [ ] **Step 1: Run focused Java tests**

Run:

```bash
mvn -pl java-backend -Dtest=ExecutionServiceFallbackTest,WorkflowKnowledgeRouteServiceTest,KnowledgeSearchServiceTest test
```

Expected: PASS.

- [ ] **Step 2: Run focused Python tests**

Run:

```bash
cd python-ai
pytest tests/test_core/test_embedding_runtime.py tests/test_core/test_knowledge_store_sql.py tests/test_core/test_knowledge_ingestion.py tests/test_api/test_knowledge_search.py -q
```

Expected: PASS.

- [ ] **Step 3: Inspect diff**

Run:

```bash
git diff -- java-backend/src/main/java/robot/agent/service/ExecutionService.java java-backend/src/main/java/robot/agent/service/WorkflowService.java java-backend/src/test/java/robot/agent/service/ExecutionServiceFallbackTest.java java-backend/src/test/java/robot/agent/service/WorkflowKnowledgeRouteServiceTest.java python-ai/src/core/settings.py python-ai/src/core/embedding_runtime.py python-ai/tests/test_core/test_embedding_runtime.py python-ai/tests/test_core/test_knowledge_store_sql.py python-ai/tests/test_core/test_knowledge_ingestion.py python-ai/tests/test_api/test_knowledge_search.py
```

Expected: diff only contains knowledge streaming, generate-answer flag, and vector dimension changes.

## Self-Review

- Spec coverage: The plan covers streaming via `message.delta`, keeping knowledge hits out of full workflow execution, disabling answer generation for chat route, default vector dimension 1024, embedding dimension alias, and focused tests.
- Migration note: Runtime migration is documented in the design spec. This plan intentionally does not drop or recreate pgvector data.
- Type consistency: Java status string is `knowledge_answer_streaming`; route decision remains `knowledge_answer`; Python request option key sent to providers is `dimensions`.
