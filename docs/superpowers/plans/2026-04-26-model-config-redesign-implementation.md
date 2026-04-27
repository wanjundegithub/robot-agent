# Model Config Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the old provider/profile-based model configuration stack with provider + model-record management, a full-screen model admin UI, and model-code-based runtime contracts across frontend, Java backend, and Python execution.

**Architecture:** Keep provider management in the existing model-config area, introduce a first-class `LlmModelRecord` entity for every callable model, and switch every runtime contract from `profile_ref` / `intent_profile_code` to `model_code` / `routing_model_code`. Java adds a focused `UnifiedModelService` for direct invocation and validation, while Python keeps local execution but consumes the same model-record contract.

**Tech Stack:** React, TypeScript, Playwright, Spring Boot, JUnit 5, Mockito, Python, pytest, httpx

---

## File Structure

### Frontend

- Modify: `frontend/src/components/ModelConfigPanel.tsx`
  Responsibility: Replace the mixed provider/profile page with a left provider editor and a right paginated model-record list.
- Modify: `frontend/src/services/api.ts`
  Responsibility: Remove profile APIs and add provider/model-record CRUD, pagination, validate, and test-chat APIs.
- Modify: `frontend/src/types/index.ts`
  Responsibility: Replace `ModelProfileConfig` types with `ModelRecordConfig` and paged response types.
- Modify: `frontend/src/App.tsx`
  Responsibility: Keep the models page mounted in the single-page shell without trailing whitespace.
- Modify: `frontend/src/index.css`
  Responsibility: Ensure the models page and its children use full-height flex layouts with internal scrolling.
- Modify: `frontend/src/components/Orchestrator.tsx`
  Responsibility: Replace workflow model binding fields from `*_profile_ref` to `*_model_code`.
- Create: `frontend/tests/e2e/model-config.spec.ts`
  Responsibility: Regression coverage for the redesigned models page.
- Modify: `frontend/tests/e2e/workflow-designer.spec.ts`
  Responsibility: Update model-binding expectations from profile refs to model codes.

### Java Backend

- Modify: `java-backend/src/main/java/robot/agent/model/LlmProviderConfig.java`
  Responsibility: Remove `default_model_code` from provider persistence.
- Delete: `java-backend/src/main/java/robot/agent/model/LlmModelProfile.java`
  Responsibility: Remove the old profile entity.
- Create: `java-backend/src/main/java/robot/agent/model/LlmModelRecord.java`
  Responsibility: Persist callable model records.
- Modify: `java-backend/src/main/java/robot/agent/repository/LlmProviderConfigRepository.java`
  Responsibility: Add provider lookup helpers used by delete protection.
- Delete: `java-backend/src/main/java/robot/agent/repository/LlmModelProfileRepository.java`
  Responsibility: Remove the old profile repository.
- Create: `java-backend/src/main/java/robot/agent/repository/LlmModelRecordRepository.java`
  Responsibility: Paging, filtering, lookup, and delete-protection queries for model records.
- Modify: `java-backend/src/main/java/robot/agent/dto/request/UpsertModelProviderRequest.java`
  Responsibility: Remove `default_model_code`.
- Delete: `java-backend/src/main/java/robot/agent/dto/request/UpsertModelProfileRequest.java`
  Responsibility: Remove the old profile DTO.
- Create: `java-backend/src/main/java/robot/agent/dto/request/UpsertModelRecordRequest.java`
  Responsibility: Create/update model-record payload.
- Modify: `java-backend/src/main/java/robot/agent/dto/request/ValidateModelProviderRequest.java`
  Responsibility: Accept `model_code` instead of `default_model_code`.
- Create: `java-backend/src/main/java/robot/agent/dto/request/TestModelRecordRequest.java`
  Responsibility: Debug invocation payload for model-record test-chat.
- Modify: `java-backend/src/main/java/robot/agent/controller/ModelConfigController.java`
  Responsibility: Expose provider + model-record resources only.
- Modify: `java-backend/src/main/java/robot/agent/service/ModelConfigService.java`
  Responsibility: Restrict it to provider/model-record CRUD, paging, and deletion rules.
- Create: `java-backend/src/main/java/robot/agent/service/UnifiedModelService.java`
  Responsibility: Provider dispatch, output extraction, usage collection, and error mapping.
- Modify: `java-backend/src/main/java/robot/agent/service/WorkflowService.java`
  Responsibility: Build runtime bundles with `model_records` and `routing_model_code`.
- Modify: `java-backend/src/main/java/robot/agent/service/ExecutionService.java`
  Responsibility: Dispatch `model_records` and `routing_model_code` to Python.
- Modify: `java-backend/src/main/java/robot/agent/service/PythonClient.java`
  Responsibility: Log and send the new runtime contract.
- Modify: `java-backend/src/main/java/robot/agent/dto/request/ExecuteRequest.java`
  Responsibility: Replace `model_profiles` / `intent_profile_code` with `model_records` / `routing_model_code`.
- Modify: `java-backend/src/main/java/robot/agent/config/DemoWorkflowDataInitializer.java`
  Responsibility: Seed providers and model records only; remove profile seeds and old workflow config bindings.

### Java Tests

- Create: `java-backend/src/test/java/robot/agent/service/ModelConfigServiceTest.java`
  Responsibility: CRUD, paging, and delete-protection tests for provider/model-record admin behavior.
- Create: `java-backend/src/test/java/robot/agent/service/UnifiedModelServiceTest.java`
  Responsibility: Provider protocol, output extraction, and error mapping tests.
- Modify: `java-backend/src/test/java/robot/agent/service/WorkflowServiceTest.java`
  Responsibility: Runtime bundle and workflow config assertions use model codes.
- Modify: `java-backend/src/test/java/robot/agent/service/ExecutionServiceTest.java`
  Responsibility: Dispatch payload assertions use `model_records` and `routing_model_code`.

### Python

- Modify: `python-ai/src/api/models.py`
  Responsibility: Accept `model_records` and `routing_model_code` in the execution payload.
- Modify: `python-ai/src/api/main.py`
  Responsibility: Build execution context with model records instead of model profiles.
- Modify: `python-ai/src/core/context.py`
  Responsibility: Rename context fields to `model_records` and `routing_model_code`.
- Modify: `python-ai/src/core/registry.py`
  Responsibility: Persist the new runtime payload shape.
- Modify: `python-ai/src/core/runtime.py`
  Responsibility: Snapshot the new model contract in replay data.
- Modify: `python-ai/src/core/subflow.py`
  Responsibility: Carry model records through subflow context inheritance.
- Modify: `python-ai/src/core/model_runtime.py`
  Responsibility: Replace profile-based helpers with model-record-based helpers.
- Modify: `python-ai/src/nodes/llm.py`
  Responsibility: Resolve explicit `model_code` and workflow defaults via model records.
- Modify: `python-ai/src/nodes/knowledge.py`
  Responsibility: Replace `model_profile_ref` references in query rewrite and answer generation.
- Modify: `python-ai/tests/test_core/test_model_runtime.py`
  Responsibility: New unit tests for model-record completion and routing.
- Modify: `python-ai/tests/test_nodes/test_llm.py`
  Responsibility: Node tests use `model_code` and `model_records`.
- Modify: `python-ai/tests/test_nodes/test_knowledge.py`
  Responsibility: Knowledge node tests use `model_code` and `model_records`.
- Modify: `python-ai/tests/test_core/test_graph_runtime.py`
  Responsibility: Runtime payload fixtures use `model_records` and `routing_model_code`.

---

### Task 1: Backend Contracts for Provider and Model Record Admin

**Files:**
- Create: `java-backend/src/test/java/robot/agent/service/ModelConfigServiceTest.java`
- Create: `java-backend/src/test/java/robot/agent/service/UnifiedModelServiceTest.java`
- Test: `java-backend/src/test/java/robot/agent/service/ModelConfigServiceTest.java`
- Test: `java-backend/src/test/java/robot/agent/service/UnifiedModelServiceTest.java`

- [ ] **Step 1: Write the failing admin-service tests**

```java
@Test
void listModelRecordsReturnsPagedRowsSortedByUpdatedAtDesc() {
    PageRequest pageRequest = PageRequest.of(0, 10);
    when(modelRecordRepository.search("doubao", "provider-a", true, pageRequest))
            .thenReturn(new PageImpl<>(List.of(modelRecord("chat-main", "Doubao Chat")), pageRequest, 1));

    Map<String, Object> page = modelConfigService.getModelRecords("doubao", "provider-a", true, 0, 10);

    assertThat(page.get("page")).isEqualTo(0);
    assertThat(page.get("page_size")).isEqualTo(10);
    assertThat(page.get("total")).isEqualTo(1L);
    assertThat((List<?>) page.get("items")).hasSize(1);
}

@Test
void deleteProviderRejectsWhenModelRecordsStillReferenceIt() {
    when(modelRecordRepository.countByProviderCode("provider-a")).thenReturn(2L);

    assertThatThrownBy(() -> modelConfigService.deleteProviderConfig("demo-admin", "provider-a"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("provider is still referenced");
}
```

- [ ] **Step 2: Write the failing unified-model tests**

```java
@Test
void invokeChatUsesModelRecordAndExtractsOpenAiText() {
    when(modelRecordRepository.findByModelCode("general-chat")).thenReturn(Optional.of(modelRecord("general-chat", "General Chat")));
    when(providerRepository.findByProviderCode("provider-a")).thenReturn(Optional.of(provider("provider-a", "openai")));
    stubProviderResponse("""
        {"choices":[{"message":{"content":"connectivity ok"}}],"usage":{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20}}
    """);

    UnifiedModelResult result = unifiedModelService.invokeChat(new UnifiedModelRequest(
            "general-chat",
            List.of(Map.of("role", "user", "content", "ping")),
            "system",
            Map.of(),
            null,
            false
    ));

    assertThat(result.text()).isEqualTo("connectivity ok");
    assertThat(result.usage().get("total_tokens")).isEqualTo(20);
}

@Test
void invokeChatMapsMissingModelToStableErrorCode() {
    when(modelRecordRepository.findByModelCode("missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> unifiedModelService.invokeChat(new UnifiedModelRequest("missing", List.of(), "", Map.of(), null, false)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("MODEL_NOT_FOUND");
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -Dtest=ModelConfigServiceTest,UnifiedModelServiceTest test`
Expected: FAIL because `LlmModelRecord`, model-record paging, provider delete protection, and `UnifiedModelService` do not exist yet.

- [ ] **Step 4: Keep the failing assertions as the contract**

No production code in this step. Confirm the failures are due to missing provider/model-record behavior, not by test setup errors.

- [ ] **Step 5: Commit**

```bash
git add java-backend/src/test/java/robot/agent/service/ModelConfigServiceTest.java java-backend/src/test/java/robot/agent/service/UnifiedModelServiceTest.java
git commit -m "test: define model record admin contracts"
```

### Task 2: Backend Persistence and Model-Config CRUD

**Files:**
- Modify: `java-backend/src/main/java/robot/agent/model/LlmProviderConfig.java`
- Delete: `java-backend/src/main/java/robot/agent/model/LlmModelProfile.java`
- Create: `java-backend/src/main/java/robot/agent/model/LlmModelRecord.java`
- Modify: `java-backend/src/main/java/robot/agent/repository/LlmProviderConfigRepository.java`
- Delete: `java-backend/src/main/java/robot/agent/repository/LlmModelProfileRepository.java`
- Create: `java-backend/src/main/java/robot/agent/repository/LlmModelRecordRepository.java`
- Modify: `java-backend/src/main/java/robot/agent/dto/request/UpsertModelProviderRequest.java`
- Delete: `java-backend/src/main/java/robot/agent/dto/request/UpsertModelProfileRequest.java`
- Create: `java-backend/src/main/java/robot/agent/dto/request/UpsertModelRecordRequest.java`
- Modify: `java-backend/src/main/java/robot/agent/dto/request/ValidateModelProviderRequest.java`
- Create: `java-backend/src/main/java/robot/agent/dto/request/TestModelRecordRequest.java`
- Modify: `java-backend/src/main/java/robot/agent/controller/ModelConfigController.java`
- Modify: `java-backend/src/main/java/robot/agent/service/ModelConfigService.java`
- Test: `java-backend/src/test/java/robot/agent/service/ModelConfigServiceTest.java`

- [ ] **Step 1: Write minimal persistence classes**

```java
@Entity
@Table(name = "llm_model_record")
public class LlmModelRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_code", length = 64, nullable = false, unique = true)
    private String modelCode;

    @Column(name = "model_name", length = 128, nullable = false)
    private String modelName;

    @Column(name = "provider_code", length = 64, nullable = false)
    private String providerCode;

    @Column(name = "upstream_model_code", length = 128, nullable = false)
    private String upstreamModelCode;

    @Column(name = "capabilities_json", columnDefinition = "JSON")
    private String capabilitiesJson;

    @Column(name = "default_system_prompt", columnDefinition = "TEXT")
    private String defaultSystemPrompt;

    @Column(name = "default_options_json", columnDefinition = "JSON")
    private String defaultOptionsJson;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;
}
```

- [ ] **Step 2: Run test to verify it still fails for missing service behavior**

Run: `mvn -Dtest=ModelConfigServiceTest test`
Expected: FAIL because controller/service methods and repository queries are still missing.

- [ ] **Step 3: Implement provider delete protection and model-record CRUD**

```java
public void deleteProviderConfig(String userId, String providerCode) {
    requireAdmin(userId, "model.provider.delete");
    long references = modelRecordRepository.countByProviderCode(providerCode);
    if (references > 0) {
        throw new ResponseStatusException(BAD_REQUEST, "provider is still referenced by " + references + " model records");
    }
    providerRepository.delete(providerRepository.findByProviderCode(providerCode)
            .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Provider not found: " + providerCode)));
}

public Map<String, Object> saveModelRecord(String userId, UpsertModelRecordRequest request) {
    requireAdmin(userId, "model.record.create");
    LlmProviderConfig provider = providerRepository.findByProviderCode(required(request.getProviderCode(), "provider_code"))
            .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Provider not found"));

    LlmModelRecord record = new LlmModelRecord();
    record.setModelCode(required(request.getModelCode(), "model_code"));
    record.setModelName(required(request.getModelName(), "model_name"));
    record.setProviderCode(provider.getProviderCode());
    record.setUpstreamModelCode(required(request.getUpstreamModelCode(), "upstream_model_code"));
    record.setCapabilitiesJson(writeJson(request.getCapabilities()));
    record.setDefaultSystemPrompt(blankToNull(request.getDefaultSystemPrompt()));
    record.setDefaultOptionsJson(writeJson(request.getDefaultOptions()));
    record.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
    return modelRecordToResponseMap(modelRecordRepository.save(record));
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=ModelConfigServiceTest test`
Expected: PASS with paged model-record listing, provider delete protection, and model-record CRUD.

- [ ] **Step 5: Commit**

```bash
git add java-backend/src/main/java/robot/agent/model/LlmProviderConfig.java java-backend/src/main/java/robot/agent/model/LlmModelRecord.java java-backend/src/main/java/robot/agent/repository/LlmProviderConfigRepository.java java-backend/src/main/java/robot/agent/repository/LlmModelRecordRepository.java java-backend/src/main/java/robot/agent/dto/request/UpsertModelProviderRequest.java java-backend/src/main/java/robot/agent/dto/request/UpsertModelRecordRequest.java java-backend/src/main/java/robot/agent/dto/request/ValidateModelProviderRequest.java java-backend/src/main/java/robot/agent/dto/request/TestModelRecordRequest.java java-backend/src/main/java/robot/agent/controller/ModelConfigController.java java-backend/src/main/java/robot/agent/service/ModelConfigService.java java-backend/src/test/java/robot/agent/service/ModelConfigServiceTest.java
git rm java-backend/src/main/java/robot/agent/model/LlmModelProfile.java java-backend/src/main/java/robot/agent/repository/LlmModelProfileRepository.java java-backend/src/main/java/robot/agent/dto/request/UpsertModelProfileRequest.java
git commit -m "feat: replace model profiles with model records"
```

### Task 3: Unified Java Model Invocation

**Files:**
- Create: `java-backend/src/main/java/robot/agent/service/UnifiedModelService.java`
- Modify: `java-backend/src/main/java/robot/agent/service/ModelConfigService.java`
- Modify: `java-backend/src/main/java/robot/agent/controller/ModelConfigController.java`
- Test: `java-backend/src/test/java/robot/agent/service/UnifiedModelServiceTest.java`

- [ ] **Step 1: Add the failing unified invocation tests to the new service file**

```java
@Test
void invokeChatUsesModelRecordAndExtractsOpenAiText() {
    when(modelRecordRepository.findByModelCode("general-chat")).thenReturn(Optional.of(modelRecord("general-chat", "General Chat")));
    when(providerRepository.findByProviderCode("provider-a")).thenReturn(Optional.of(provider("provider-a", "openai")));
    stubProviderResponse("""
        {"choices":[{"message":{"content":"connectivity ok"}}],"usage":{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20}}
    """);

    UnifiedModelResult result = unifiedModelService.invokeChat(new UnifiedModelRequest(
            "general-chat",
            List.of(Map.of("role", "user", "content", "ping")),
            "system",
            Map.of(),
            null,
            false
    ));

    assertThat(result.text()).isEqualTo("connectivity ok");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=UnifiedModelServiceTest test`
Expected: FAIL because `UnifiedModelService` is not implemented.

- [ ] **Step 3: Implement the minimal unified request/response and invocation flow**

```java
public record UnifiedModelRequest(
        String modelCode,
        List<Map<String, Object>> messages,
        String systemPrompt,
        Map<String, Object> options,
        Map<String, Object> responseFormat,
        boolean stream
) {}

public record UnifiedModelResult(
        String text,
        Map<String, Object> structuredData,
        String finishReason,
        Map<String, Object> usage,
        Map<String, Object> rawProviderResponse
) {}

public UnifiedModelResult invokeChat(UnifiedModelRequest request) {
    LlmModelRecord record = modelRecordRepository.findByModelCode(required(request.modelCode(), "modelCode"))
            .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "MODEL_NOT_FOUND"));
    LlmProviderConfig provider = providerRepository.findByProviderCode(record.getProviderCode())
            .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "PROVIDER_NOT_FOUND"));
    ProviderRequest providerRequest = buildProviderRequest(provider, record, request);
    Map<String, Object> payload = executeProviderRequest(providerRequest, record);
    return extractUnifiedResult(provider.getProviderType(), payload);
}
```

- [ ] **Step 4: Route model-record validate and test-chat endpoints through the unified service**

```java
public Map<String, Object> validateModelRecord(String userId, String modelCode) {
    requireAdmin(userId, "model.record.validate");
    UnifiedModelResult result = unifiedModelService.invokeChat(new UnifiedModelRequest(
            modelCode,
            List.of(Map.of("role", "user", "content", "Please reply: connectivity ok")),
            "You are a connectivity validation assistant.",
            Map.of(),
            null,
            false
    ));
    return Map.of("valid", true, "model_code", modelCode, "message", result.text(), "usage", result.usage());
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -Dtest=UnifiedModelServiceTest test`
Expected: PASS with text extraction and stable error mapping.

- [ ] **Step 6: Commit**

```bash
git add java-backend/src/main/java/robot/agent/service/UnifiedModelService.java java-backend/src/main/java/robot/agent/service/ModelConfigService.java java-backend/src/main/java/robot/agent/controller/ModelConfigController.java java-backend/src/test/java/robot/agent/service/UnifiedModelServiceTest.java
git commit -m "feat: add unified model invocation service"
```

### Task 4: Java Workflow and Execution Runtime Contracts

**Files:**
- Modify: `java-backend/src/main/java/robot/agent/dto/request/ExecuteRequest.java`
- Modify: `java-backend/src/main/java/robot/agent/service/WorkflowService.java`
- Modify: `java-backend/src/main/java/robot/agent/service/ExecutionService.java`
- Modify: `java-backend/src/main/java/robot/agent/service/PythonClient.java`
- Modify: `java-backend/src/test/java/robot/agent/service/WorkflowServiceTest.java`
- Modify: `java-backend/src/test/java/robot/agent/service/ExecutionServiceTest.java`

- [ ] **Step 1: Write the failing Java runtime-contract tests**

```java
@Test
void buildRuntimeExecutionBundleUsesRoutingModelCodeAndModelRecords() {
    WorkflowService.RuntimeExecutionBundle bundle = workflowService.buildRuntimeExecutionBundle("general_query", "1.0.0", definition, Map.of(), Map.of());

    assertThat(bundle.modelRecords()).isNotEmpty();
    assertThat(bundle.routingModelCode()).isEqualTo("intent-router");
}

@Test
void startExecutionDispatchesModelRecordsToPython() {
    executionService.startExecution("session-1", request);

    ArgumentCaptor<ExecuteRequest> requestCaptor = ArgumentCaptor.forClass(ExecuteRequest.class);
    verify(pythonClient).execute(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getModelRecords()).isNotEmpty();
    assertThat(requestCaptor.getValue().getRoutingModelCode()).isEqualTo("intent-router");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=WorkflowServiceTest,ExecutionServiceTest test`
Expected: FAIL because runtime bundles and execute payloads still use `modelProfiles` and `intentProfileCode`.

- [ ] **Step 3: Implement the minimal runtime-contract rename**

```java
public record RuntimeExecutionBundle(
        Map<String, Object> workflowDefinition,
        Map<String, Object> entryRule,
        Map<String, Object> workflowConfig,
        Map<String, Map<String, Object>> workflowCatalog,
        List<Map<String, Object>> providerConfigs,
        List<Map<String, Object>> modelRecords,
        String routingModelCode
) {}

@JsonProperty("model_records")
private List<Map<String, Object>> modelRecords;

@JsonProperty("routing_model_code")
private String routingModelCode;
```

- [ ] **Step 4: Replace workflow config keys from profile refs to model codes**

```java
workflowVersion.setConfig(objectMapper.writeValueAsString(Map.of(
        "routing_model_code", "intent-router",
        "llm_defaults", Map.of("model_code", "general-chat")
)));

executeInput.put("routing_model_code", runtimeBundle.routingModelCode());
executeRequest.setModelRecords(runtimeBundle.modelRecords());
executeRequest.setRoutingModelCode(runtimeBundle.routingModelCode());
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -Dtest=WorkflowServiceTest,ExecutionServiceTest test`
Expected: PASS with `model_records` and `routing_model_code` flowing into Python dispatch.

- [ ] **Step 6: Commit**

```bash
git add java-backend/src/main/java/robot/agent/dto/request/ExecuteRequest.java java-backend/src/main/java/robot/agent/service/WorkflowService.java java-backend/src/main/java/robot/agent/service/ExecutionService.java java-backend/src/main/java/robot/agent/service/PythonClient.java java-backend/src/test/java/robot/agent/service/WorkflowServiceTest.java java-backend/src/test/java/robot/agent/service/ExecutionServiceTest.java
git commit -m "feat: switch java runtime contracts to model codes"
```

### Task 5: Python Runtime Switch to Model Records

**Files:**
- Modify: `python-ai/src/api/models.py`
- Modify: `python-ai/src/api/main.py`
- Modify: `python-ai/src/core/context.py`
- Modify: `python-ai/src/core/registry.py`
- Modify: `python-ai/src/core/runtime.py`
- Modify: `python-ai/src/core/subflow.py`
- Modify: `python-ai/src/core/model_runtime.py`
- Modify: `python-ai/src/nodes/llm.py`
- Modify: `python-ai/src/nodes/knowledge.py`
- Modify: `python-ai/tests/test_core/test_model_runtime.py`
- Modify: `python-ai/tests/test_nodes/test_llm.py`
- Modify: `python-ai/tests/test_nodes/test_knowledge.py`
- Modify: `python-ai/tests/test_core/test_graph_runtime.py`

- [ ] **Step 1: Write the failing Python tests for model-record resolution**

```python
@pytest.mark.asyncio
async def test_execute_model_record_completion_calls_openai_compatible_provider():
    provider_configs = {
        "provider-a": {
            "provider_code": "provider-a",
            "provider_type": "openai_compatible",
            "base_url": "https://llm.example.com/v1",
        }
    }
    model_records = {
        "general-chat": {
            "model_code": "general-chat",
            "provider_code": "provider-a",
            "upstream_model_code": "qwen-plus",
            "default_options": {"temperature": 0.2, "top_p": 0.9, "max_tokens": 256, "timeout_sec": 10},
        }
    }

    with patch("src.core.model_runtime.httpx.AsyncClient") as mock_client:
        mock_response = Mock()
        mock_response.json.return_value = {
            "choices": [{"message": {"content": "structured output"}}]
        }
        mock_response.raise_for_status.return_value = None
        mock_instance = AsyncMock()
        mock_instance.__aenter__.return_value = mock_instance
        mock_instance.__aexit__.return_value = None
        mock_instance.post.return_value = mock_response
        mock_client.return_value = mock_instance

        result = await execute_model_record_completion(
            model_code="general-chat",
            provider_configs=provider_configs,
            model_records=model_records,
            system_prompt="system",
            user_prompt="user",
        )

    assert result == "structured output"
```

```python
@pytest.mark.asyncio
async def test_llm_node_uses_model_code_from_workflow_defaults(monkeypatch):
    context = ExecutionContext(
        execution_id="exec_test",
        session_id="sess_test",
        workflow_code="flight_booking",
        workflow_version="1.0.0",
        workflow_config={"llm_defaults": {"model_code": "structured-extraction"}},
        provider_configs={"provider-a": {"provider_code": "provider-a", "provider_type": "openai_compatible", "base_url": "https://llm.example.com/v1"}},
        model_records={"structured-extraction": {"model_code": "structured-extraction", "provider_code": "provider-a", "upstream_model_code": "qwen-plus"}},
    )
    context.add_execution_variable("user_message", "from Beijing to Shanghai")
    node = LLMNode("extract_slots", {
        "config": {
            "prompt": "extract slots",
            "structured_output": {
                "enabled": True,
                "schema": {
                    "type": "object",
                    "properties": {
                        "departure_city": {"type": "string"},
                        "arrival_city": {"type": "string"},
                    },
                },
            },
        },
    })
    monkeypatch.setattr(
        "src.nodes.llm.execute_model_record_completion",
        async_result('{"departure_city":"Beijing","arrival_city":"Shanghai"}'),
    )

    result = await node.execute(context)

    assert result["output"]["departure_city"] == "Beijing"
    assert result["output"]["arrival_city"] == "Shanghai"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest python-ai/tests/test_core/test_model_runtime.py python-ai/tests/test_nodes/test_llm.py python-ai/tests/test_nodes/test_knowledge.py -q`
Expected: FAIL because Python still resolves `model_profiles`, `intent_profile_code`, and `model_profile_ref`.

- [ ] **Step 3: Implement the minimal payload and context rename**

```python
class ExecuteRequest(BaseModel):
    provider_configs: list[Dict[str, Any]] = Field(default_factory=list)
    model_records: list[Dict[str, Any]] = Field(default_factory=list)
    routing_model_code: Optional[str] = Field(default=None)

@dataclass
class ExecutionContext:
    provider_configs: Dict[str, Dict[str, Any]] = field(default_factory=dict)
    model_records: Dict[str, Dict[str, Any]] = field(default_factory=dict)
    routing_model_code: Optional[str] = None
```

- [ ] **Step 4: Replace profile-based runtime helpers and node lookups**

```python
def resolve_model_record(model_records: Dict[str, Dict[str, Any]], model_code: str) -> Dict[str, Any]:
    record = model_records.get(model_code)
    if not record:
        raise ModelConfigError(f"Model record not found: {model_code}")
    return record

async def execute_model_record_completion(
    model_code: str,
    provider_configs: Dict[str, Dict[str, Any]],
    model_records: Dict[str, Dict[str, Any]],
    system_prompt: str,
    user_prompt: str,
    response_format: Dict[str, Any] | None = None,
) -> str:
    record = resolve_model_record(model_records, model_code)
    provider = resolve_provider(provider_configs, str(record.get("provider_code")))
    return await _invoke_provider(provider, record, system_prompt, user_prompt, response_format)
```

```python
workflow_defaults = context.workflow_config.get("llm_defaults", {}) if isinstance(context.workflow_config, dict) else {}
model_code = self.model_code or workflow_defaults.get("model_code")
if not model_code:
    raise ValueError(f"model_code is required for node {self.node_id}")
```

- [ ] **Step 5: Run test to verify it passes**

Run: `pytest python-ai/tests/test_core/test_model_runtime.py python-ai/tests/test_nodes/test_llm.py python-ai/tests/test_nodes/test_knowledge.py python-ai/tests/test_core/test_graph_runtime.py -q`
Expected: PASS with `model_records` and `routing_model_code` used consistently.

- [ ] **Step 6: Commit**

```bash
git add python-ai/src/api/models.py python-ai/src/api/main.py python-ai/src/core/context.py python-ai/src/core/registry.py python-ai/src/core/runtime.py python-ai/src/core/subflow.py python-ai/src/core/model_runtime.py python-ai/src/nodes/llm.py python-ai/src/nodes/knowledge.py python-ai/tests/test_core/test_model_runtime.py python-ai/tests/test_nodes/test_llm.py python-ai/tests/test_nodes/test_knowledge.py python-ai/tests/test_core/test_graph_runtime.py
git commit -m "feat: switch python runtime to model records"
```

### Task 6: Frontend Model Config Page and Workflow Binding Cleanup

**Files:**
- Create: `frontend/tests/e2e/model-config.spec.ts`
- Modify: `frontend/src/components/ModelConfigPanel.tsx`
- Modify: `frontend/src/services/api.ts`
- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/index.css`
- Modify: `frontend/src/components/Orchestrator.tsx`
- Modify: `frontend/tests/e2e/workflow-designer.spec.ts`

- [ ] **Step 1: Write the failing Playwright regression for the redesigned models page**

```ts
test.describe('model config redesign', () => {
  test('shows provider editor plus paginated model-record list without legacy profile UI', async ({ page }) => {
    await page.route('**/api/model-config/providers', async (route) => {
      await route.fulfill({ json: [{ provider_code: 'provider-a', provider_name: '豆包生产', provider_type: 'doubao', base_url: 'https://ark.example.com', enabled: true }] })
    })
    await page.route('**/api/model-config/models**', async (route) => {
      await route.fulfill({
        json: {
          items: [
            {
              model_code: 'general-chat',
              model_name: '通用对话',
              provider_code: 'provider-a',
              provider_name: '豆包生产',
              provider_type: 'doubao',
              upstream_model_code: 'doubao-seed-2-0-pro-260215',
              capabilities: ['text', 'stream'],
              enabled: true,
              updated_at: '2026-04-26T21:00:00',
            },
          ],
          page: 0,
          page_size: 10,
          total: 1,
        },
      })
    })

    await page.goto('/#models')

    await expect(page.getByText('业务模型配置')).toHaveCount(0)
    await expect(page.getByText('默认 OpenAI 提供方')).toHaveCount(0)
    await expect(page.getByTestId('model-provider-panel')).toBeVisible()
    await expect(page.getByTestId('model-record-list')).toBeVisible()
    await expect(page.getByTestId('model-record-pagination')).toBeVisible()
    await expect(page.getByText('通用对话')).toBeVisible()
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm run test:e2e -- --grep "model config redesign"`
Expected: FAIL because the current page still renders the business-profile block and has no paginated model-record list.

- [ ] **Step 3: Replace frontend types and APIs with provider + model-record contracts**

```ts
export interface ModelRecordConfig {
  model_code: string
  model_name: string
  provider_code: string
  provider_name?: string | null
  provider_type: string
  upstream_model_code: string
  capabilities: string[]
  default_system_prompt?: string | null
  default_options?: Record<string, unknown>
  enabled: boolean
  created_at?: string
  updated_at?: string
}

export async function getModelRecords(params: {
  page: number
  pageSize: number
  keyword?: string
  providerCode?: string
  enabled?: boolean
}): Promise<PagedModelRecordResponse> {
  const query = new URLSearchParams()
  query.set('page', String(params.page))
  query.set('pageSize', String(params.pageSize))
  if (params.keyword) query.set('keyword', params.keyword)
  if (params.providerCode) query.set('providerCode', params.providerCode)
  if (typeof params.enabled === 'boolean') query.set('enabled', String(params.enabled))
  const response = await fetch(`${API_BASE_URL}/model-config/models?${query.toString()}`)
  if (!response.ok) await parseApiError(response)
  return await response.json()
}
```

- [ ] **Step 4: Rewrite `ModelConfigPanel` to full-height provider + model-list layout**

```tsx
return (
  <div data-testid="model-config-layout" className="panel-card h-full min-h-0">
    <div className="flex h-full min-h-0 gap-4">
      <section data-testid="model-provider-panel" className="flex h-full min-h-0 w-[360px] flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white">
        <div className="border-b border-slate-200 px-4 py-3">
          <div className="text-sm font-semibold text-slate-900">服务商配置</div>
        </div>
        <div className="min-h-0 flex-1 overflow-auto px-4 py-4">
          <input value={providerForm.provider_name} onChange={(event) => updateProviderForm({ provider_name: event.target.value })} />
          <input value={providerForm.base_url} onChange={(event) => updateProviderForm({ base_url: event.target.value })} />
          <button onClick={() => void handleValidateProvider()}>测试连通性</button>
          <button onClick={() => void handleSaveProvider()}>保存服务商</button>
        </div>
      </section>

      <section className="flex min-h-0 flex-1 flex-col rounded-2xl border border-slate-200 bg-white">
        <div className="flex items-center justify-between gap-3 border-b border-slate-200 px-4 py-3">
          <input value={keyword} onChange={(event) => setKeyword(event.target.value)} />
          <button onClick={() => setEditorState(createEmptyModelRecord())}>新建模型记录</button>
        </div>
        <div data-testid="model-record-list" className="min-h-0 flex-1 overflow-auto">
          {records.map((record) => (
            <button key={record.model_code} onClick={() => setEditorState(record)}>
              {record.model_name}
            </button>
          ))}
        </div>
        <div data-testid="model-record-pagination" className="border-t border-slate-200 px-4 py-3">
          <button disabled={page === 0} onClick={() => setPage((current) => current - 1)}>上一页</button>
          <span>{page + 1}</span>
          <button disabled={(page + 1) * pageSize >= total} onClick={() => setPage((current) => current + 1)}>下一页</button>
        </div>
      </section>
    </div>
  </div>
)
```

- [ ] **Step 5: Replace workflow model bindings from profile refs to model codes**

```ts
interface ModelBindingsState {
  routing_model_code: string
  llm_defaults: {
    model_code: string
  }
}

const defaultModelBindings: ModelBindingsState = {
  routing_model_code: 'intent-router',
  llm_defaults: { model_code: 'general-chat' },
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `npm run test:e2e -- --grep "model config redesign|workflow designer v2 contract"`
Expected: PASS

Run: `npm run build`
Expected: PASS

Run: `npm run check:text`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/ModelConfigPanel.tsx frontend/src/services/api.ts frontend/src/types/index.ts frontend/src/App.tsx frontend/src/index.css frontend/src/components/Orchestrator.tsx frontend/tests/e2e/model-config.spec.ts frontend/tests/e2e/workflow-designer.spec.ts
git commit -m "feat: redesign model config ui around model records"
```

### Task 7: Seed Cleanup and Final Verification

**Files:**
- Modify: `java-backend/src/main/java/robot/agent/config/DemoWorkflowDataInitializer.java`
- Modify: `java-backend/src/test/java/robot/agent/service/WorkflowServiceTest.java`
- Modify: `java-backend/src/test/java/robot/agent/service/ExecutionServiceTest.java`
- Modify: `python-ai/tests/test_core/test_graph_runtime.py`
- Test: `frontend/tests/e2e/model-config.spec.ts`
- Test: `java-backend/src/test/java/robot/agent/service/ModelConfigServiceTest.java`
- Test: `java-backend/src/test/java/robot/agent/service/UnifiedModelServiceTest.java`
- Test: `python-ai/tests/test_core/test_model_runtime.py`

- [ ] **Step 1: Replace seeded profiles and workflow config with model records**

```java
private void seedModelConfigs() {
    seedProvider("openai-compatible-prod", "OpenAI Compatible Prod", "openai", "https://api.example.com/v1");
    seedModelRecord("intent-router", "Intent Router", "openai-compatible-prod", "gpt-4o-mini", List.of("text", "json"), Map.of("temperature", 0.1, "top_p", 0.8, "max_tokens", 512, "timeout_sec", 15));
    seedModelRecord("general-chat", "General Chat", "openai-compatible-prod", "gpt-4o-mini", List.of("text", "stream"), Map.of("temperature", 0.3, "top_p", 0.95, "max_tokens", 1024, "timeout_sec", 15));
}

workflowVersion.setConfig(objectMapper.writeValueAsString(Map.of(
        "routing_model_code", "intent-router",
        "llm_defaults", Map.of("model_code", "general-chat")
)));
```

- [ ] **Step 2: Run backend targeted verification**

Run: `mvn -Dtest=ModelConfigServiceTest,UnifiedModelServiceTest,WorkflowServiceTest,ExecutionServiceTest test`
Expected: PASS

- [ ] **Step 3: Run Python targeted verification**

Run: `pytest python-ai/tests/test_core/test_model_runtime.py python-ai/tests/test_nodes/test_llm.py python-ai/tests/test_nodes/test_knowledge.py python-ai/tests/test_core/test_graph_runtime.py -q`
Expected: PASS

- [ ] **Step 4: Run frontend targeted verification**

Run: `npm run test:e2e -- --grep "model config redesign"`
Expected: PASS

- [ ] **Step 5: Review the diff for scope discipline**

Run: `git diff -- frontend/src/components/ModelConfigPanel.tsx frontend/src/services/api.ts frontend/src/types/index.ts frontend/src/App.tsx frontend/src/index.css frontend/src/components/Orchestrator.tsx frontend/tests/e2e/model-config.spec.ts frontend/tests/e2e/workflow-designer.spec.ts java-backend/src/main/java/robot/agent/model/LlmProviderConfig.java java-backend/src/main/java/robot/agent/model/LlmModelRecord.java java-backend/src/main/java/robot/agent/controller/ModelConfigController.java java-backend/src/main/java/robot/agent/service/ModelConfigService.java java-backend/src/main/java/robot/agent/service/UnifiedModelService.java java-backend/src/main/java/robot/agent/service/WorkflowService.java java-backend/src/main/java/robot/agent/service/ExecutionService.java java-backend/src/main/java/robot/agent/service/PythonClient.java java-backend/src/main/java/robot/agent/config/DemoWorkflowDataInitializer.java python-ai/src/api/models.py python-ai/src/core/context.py python-ai/src/core/model_runtime.py python-ai/src/nodes/llm.py python-ai/src/nodes/knowledge.py`
Expected: Only provider/model-record redesign, runtime model-code switching, and verification changes.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/ModelConfigPanel.tsx frontend/src/services/api.ts frontend/src/types/index.ts frontend/src/App.tsx frontend/src/index.css frontend/src/components/Orchestrator.tsx frontend/tests/e2e/model-config.spec.ts frontend/tests/e2e/workflow-designer.spec.ts java-backend/src/main/java/robot/agent/model/LlmProviderConfig.java java-backend/src/main/java/robot/agent/model/LlmModelRecord.java java-backend/src/main/java/robot/agent/repository/LlmModelRecordRepository.java java-backend/src/main/java/robot/agent/controller/ModelConfigController.java java-backend/src/main/java/robot/agent/service/ModelConfigService.java java-backend/src/main/java/robot/agent/service/UnifiedModelService.java java-backend/src/main/java/robot/agent/service/WorkflowService.java java-backend/src/main/java/robot/agent/service/ExecutionService.java java-backend/src/main/java/robot/agent/service/PythonClient.java java-backend/src/main/java/robot/agent/config/DemoWorkflowDataInitializer.java java-backend/src/test/java/robot/agent/service/ModelConfigServiceTest.java java-backend/src/test/java/robot/agent/service/UnifiedModelServiceTest.java java-backend/src/test/java/robot/agent/service/WorkflowServiceTest.java java-backend/src/test/java/robot/agent/service/ExecutionServiceTest.java python-ai/src/api/models.py python-ai/src/api/main.py python-ai/src/core/context.py python-ai/src/core/registry.py python-ai/src/core/runtime.py python-ai/src/core/subflow.py python-ai/src/core/model_runtime.py python-ai/src/nodes/llm.py python-ai/src/nodes/knowledge.py python-ai/tests/test_core/test_model_runtime.py python-ai/tests/test_nodes/test_llm.py python-ai/tests/test_nodes/test_knowledge.py python-ai/tests/test_core/test_graph_runtime.py
git commit -m "feat: unify model config around model records"
```
