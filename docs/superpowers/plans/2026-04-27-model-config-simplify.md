# Model Config Simplify Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Simplify the model configuration page and admin API to a Chinese-first full-screen workspace that only manages custom model name, provider, model name, API key, and base URL while preserving runtime compatibility for existing workflow execution.

**Architecture:** Keep the current workflow/runtime model-code path internally so existing workflow execution does not break, but collapse the admin-facing page and DTOs into a single simplified model configuration record. Internally, each saved record will continue to map to a hidden stable model/provider identity so Java and Python runtime code can still resolve models safely.

**Tech Stack:** React + TypeScript + Playwright, Spring Boot + JPA + Mockito/JUnit

---

### Task 1: Lock the new frontend behavior with failing tests

**Files:**
- Modify: `frontend/tests/e2e/model-config.spec.ts`

- [ ] **Step 1: Rewrite the model-config E2E assertions to the new simplified UX**

```ts
await expect(page.getByTestId('models-page-layout')).toBeVisible()
await expect(page.getByTestId('model-config-layout')).toBeVisible()
await expect(page.getByText('新建模型记录')).toHaveCount(0)
await expect(page.getByTestId('model-config-sidebar')).toBeVisible()
await expect(page.getByTestId('model-config-list')).toBeVisible()
await expect(page.getByTestId('model-config-search-input')).toBeVisible()
```

- [ ] **Step 2: Add search, edit-form, and test-call expectations**

```ts
await page.getByTestId('model-config-search-input').fill('gpt-4o')
await page.getByTestId('model-config-search-apply').click()
await expect(page.getByText('通用对话模型')).toBeVisible()

await page.getByTestId('model-config-row-1').click()
await expect(page.getByLabel('自定义模型名')).toHaveValue('通用对话模型')
await expect(page.getByLabel('供应商')).toHaveValue('openai')
await expect(page.getByLabel('Model 名称（实际调用模型）')).toHaveValue('gpt-4o-mini')
```

- [ ] **Step 3: Run the E2E test to verify it fails against the current UI**

Run: `npm run test:e2e -- model-config.spec.ts`
Expected: FAIL because the current page still exposes provider CRUD, legacy fields, and old test ids.

### Task 2: Lock the new backend DTO/service behavior with failing tests

**Files:**
- Modify: `java-backend/src/test/java/robot/agent/service/ModelConfigServiceTest.java`
- Modify: `java-backend/src/test/java/robot/agent/service/UnifiedModelServiceTest.java`

- [ ] **Step 1: Update service tests to expect simplified admin fields**

```java
assertThat(first.get("custom_model_name")).isEqualTo("Doubao Chat");
assertThat(first.get("provider")).isEqualTo("doubao");
assertThat(first.get("model_name")).isEqualTo("doubao-seed-2-0-pro-260215");
assertThat(first.get("base_url")).isEqualTo("https://api.example.com");
assertThat(first).doesNotContainKeys("capabilities", "default_system_prompt", "default_options", "enabled");
```

- [ ] **Step 2: Add a direct HTTP test-call assertion for the simplified payload**

```java
Map<String, Object> result = modelConfigService.testSimpleModelConnection("demo-admin", request);
assertThat(result.get("ok")).isEqualTo(true);
assertThat(result.get("provider")).isEqualTo("openai");
assertThat(result.get("model_name")).isEqualTo("general-chat");
assertThat(result.get("answer")).isEqualTo("connectivity ok");
```

- [ ] **Step 3: Run the Java model-config tests to verify they fail**

Run: `mvn -pl java-backend -Dtest=ModelConfigServiceTest,UnifiedModelServiceTest test`
Expected: FAIL because the current service still returns `model_code/provider_code/upstream_model_code` and only supports record-based test-chat.

### Task 3: Implement the simplified backend model admin contract

**Files:**
- Modify: `java-backend/src/main/java/robot/agent/model/LlmModelRecord.java`
- Modify: `java-backend/src/main/java/robot/agent/dto/request/UpsertModelRecordRequest.java`
- Modify: `java-backend/src/main/java/robot/agent/controller/ModelConfigController.java`
- Modify: `java-backend/src/main/java/robot/agent/repository/LlmModelRecordRepository.java`
- Modify: `java-backend/src/main/java/robot/agent/service/ModelConfigService.java`
- Modify: `java-backend/src/main/java/robot/agent/service/UnifiedModelService.java`

- [ ] **Step 1: Simplify the admin-facing model record request/response fields**

```java
@JsonProperty("custom_model_name")
private String customModelName;
private String provider;
@JsonProperty("model_name")
private String modelName;
@JsonProperty("api_key")
private String apiKey;
@JsonProperty("base_url")
private String baseUrl;
```

- [ ] **Step 2: Keep hidden runtime compatibility while persisting the simplified record**

```java
modelRecord.setModelName(required(request.getCustomModelName(), "custom_model_name"));
modelRecord.setUpstreamModelCode(required(request.getModelName(), "model_name"));
modelRecord.setProviderType(required(request.getProvider(), "provider"));
```

- [ ] **Step 3: Upsert an internal provider snapshot from the simplified record**

```java
provider.setProviderType(required(request.getProvider(), "provider"));
provider.setBaseUrl(required(request.getBaseUrl(), "base_url").replaceAll("/+$", ""));
provider.setApiKeySecretRef(blankToNull(request.getApiKey()));
provider.setEnabled(true);
```

- [ ] **Step 4: Add a simplified direct HTTP test entrypoint**

```java
UnifiedModelResult result = unifiedModelService.invokeDirectChat(
        request.getProvider(),
        request.getBaseUrl(),
        request.getApiKey(),
        request.getModelName(),
        List.of(Map.of("role", "user", "content", "ping")),
        null
);
```

- [ ] **Step 5: Run the targeted Java tests to verify they pass**

Run: `mvn -pl java-backend -Dtest=ModelConfigServiceTest,UnifiedModelServiceTest test`
Expected: PASS

### Task 4: Implement the simplified full-screen frontend workspace

**Files:**
- Modify: `frontend/src/components/ModelConfigPanel.tsx`
- Modify: `frontend/src/services/api.ts`
- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/index.css`

- [ ] **Step 1: Replace legacy provider CRUD + old model editor state with a simplified single-record form**

```ts
type ModelConfigFormState = {
  id?: number
  custom_model_name: string
  provider: string
  model_name: string
  api_key: string
  base_url: string
}
```

- [ ] **Step 2: Make the workspace full-height with 3:7 horizontal layout**

```tsx
<div className="model-config-workspace" data-testid="model-config-layout">
  <aside className="model-config-sidebar" data-testid="model-config-sidebar">...</aside>
  <section className="model-config-list" data-testid="model-config-list">...</section>
</div>
```

- [ ] **Step 3: Remove the right-side create button and keep search/list only**

```tsx
<input data-testid="model-config-search-input" ... />
<button data-testid="model-config-search-apply" ...>查找模型</button>
```

- [ ] **Step 4: Translate all admin copy to Chinese except selectable provider/model values**

```tsx
<Field label="自定义模型名" hint="用于页面识别的名称" />
<Field label="供应商" hint="模型服务提供方" />
<Field label="Model 名称（实际调用模型）" hint="发送给上游接口的模型名" />
<Field label="API Key（接口密钥）" hint="调用上游模型接口使用的密钥" />
<Field label="Base URL（接口地址）" hint="上游模型接口的根地址" />
```

- [ ] **Step 5: Run the model-config E2E test to verify it passes**

Run: `npm run test:e2e -- model-config.spec.ts`
Expected: PASS

### Task 5: Final verification

**Files:**
- No new files beyond the implementation/test changes above

- [ ] **Step 1: Run frontend build**

Run: `npm run build`
Expected: PASS

- [ ] **Step 2: Re-run targeted backend tests**

Run: `mvn -pl java-backend -Dtest=ModelConfigServiceTest,UnifiedModelServiceTest test`
Expected: PASS

- [ ] **Step 3: Re-run model-config E2E**

Run: `npm run test:e2e -- model-config.spec.ts`
Expected: PASS
