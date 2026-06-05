# API Center Auth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an API Center authentication center with API-group defaults and API-item inherit/none/custom strategies for No Auth, API Key, Bearer Token, Basic Auth, and Digest Auth.

**Architecture:** Backend owns persistence, encryption, effective-auth resolution, request injection, and Digest challenge retry. Frontend owns API group/API item auth forms, non-blocking Header-auth hints, effective-auth previews, and request-test draft payload wiring. Backend and frontend work can run in parallel because their write scopes are disjoint.

**Tech Stack:** Java 17, Spring Boot, Spring Data JPA, JUnit 5, Mockito, AssertJ, React, TypeScript, Vite, Playwright.

**Important Constraints:** Do not commit changes. Preserve UTF-8 without BOM. Do not modify files outside the worker-owned write scope. Header auth fields are allowed and must not block save/test; explicit Headers and URL query parameters take precedence over generated auth values.

---

## File Structure

### Backend Worker Scope

- Create: `java-backend/src/main/java/robot/agent/apicenter/model/ApiAuthConfig.java`
- Create: `java-backend/src/main/java/robot/agent/apicenter/model/ApiAuthMode.java`
- Create: `java-backend/src/main/java/robot/agent/apicenter/model/ApiAuthType.java`
- Create: `java-backend/src/main/java/robot/agent/apicenter/model/ApiAuthScopeType.java`
- Create: `java-backend/src/main/java/robot/agent/apicenter/repository/ApiAuthConfigRepository.java`
- Create: `java-backend/src/main/java/robot/agent/apicenter/service/ApiAuthConfigService.java`
- Create: `java-backend/src/main/java/robot/agent/apicenter/service/ApiAuthCryptoService.java`
- Create: `java-backend/src/main/java/robot/agent/apicenter/service/ApiAuthResolver.java`
- Create: `java-backend/src/main/java/robot/agent/apicenter/service/ApiDigestAuthService.java`
- Create: `java-backend/src/test/java/robot/agent/apicenter/service/ApiAuthResolverTest.java`
- Create: `java-backend/src/test/java/robot/agent/apicenter/service/ApiDigestAuthServiceTest.java`
- Modify: `java-backend/src/main/java/robot/agent/apicenter/model/ApiItem.java`
- Modify: `java-backend/src/main/java/robot/agent/apicenter/controller/ApiCenterController.java`
- Modify: `java-backend/src/main/java/robot/agent/apicenter/service/ApiCenterService.java`
- Modify: `java-backend/src/main/java/robot/agent/apicenter/service/ApiRuntimeResolver.java`
- Modify: `java-backend/src/test/java/robot/agent/apicenter/service/ApiCenterServiceSaveGateTest.java`

### Frontend Worker Scope

- Modify: `frontend/src/components/ApiCenterPanel.tsx`
- Modify: `frontend/src/services/api.ts`
- Modify: `frontend/tests/e2e/api-center.spec.ts`

---

## Backend Tasks

### Task B1: Add Auth Persistence Model

**Files:**
- Create: `java-backend/src/main/java/robot/agent/apicenter/model/ApiAuthConfig.java`
- Create: `java-backend/src/main/java/robot/agent/apicenter/model/ApiAuthMode.java`
- Create: `java-backend/src/main/java/robot/agent/apicenter/model/ApiAuthType.java`
- Create: `java-backend/src/main/java/robot/agent/apicenter/model/ApiAuthScopeType.java`
- Create: `java-backend/src/main/java/robot/agent/apicenter/repository/ApiAuthConfigRepository.java`
- Modify: `java-backend/src/main/java/robot/agent/apicenter/model/ApiItem.java`

- [ ] **Step B1.1: Add enum types**

Create `ApiAuthType` with values `NO_AUTH`, `API_KEY`, `BEARER`, `BASIC`, `DIGEST`.

Create `ApiAuthMode` with values `INHERIT`, `NONE`, `CUSTOM`.

Create `ApiAuthScopeType` with values `GROUP`, `ITEM`.

- [ ] **Step B1.2: Add `ApiAuthConfig` entity**

Implement table `api_auth_config` with fields `id`, `scopeType`, `scopeId`, `authType`, `configCiphertext`, `preview`, `createdAt`, `updatedAt`. Store enum values as strings. Add a uniqueness constraint over `scope_type` and `scope_id` so each group/item has at most one config.

- [ ] **Step B1.3: Add repository**

Create `ApiAuthConfigRepository` extending `JpaRepository<ApiAuthConfig, Long>` with:

```java
Optional<ApiAuthConfig> findByScopeTypeAndScopeId(ApiAuthScopeType scopeType, Long scopeId);
void deleteByScopeTypeAndScopeId(ApiAuthScopeType scopeType, Long scopeId);
```

- [ ] **Step B1.4: Add `authMode` to `ApiItem`**

Add `@Column(name = "auth_mode", length = 32, nullable = false)` with default value `"INHERIT"`. Existing rows must behave as inherit when the DB value is null.

- [ ] **Step B1.5: Compile check for model task**

Run: `mvn -pl java-backend -DskipTests compile` from repo root, or run `mvn -DskipTests compile` from `java-backend` if the root is not a Maven aggregator.

Expected: compile succeeds or only unrelated pre-existing failures are reported.

### Task B2: Add Auth Crypto and Config Service

**Files:**
- Create: `java-backend/src/main/java/robot/agent/apicenter/service/ApiAuthCryptoService.java`
- Create: `java-backend/src/main/java/robot/agent/apicenter/service/ApiAuthConfigService.java`
- Modify: `java-backend/src/main/java/robot/agent/apicenter/controller/ApiCenterController.java`
- Test: `java-backend/src/test/java/robot/agent/apicenter/service/ApiAuthResolverTest.java`

- [ ] **Step B2.1: Write failing tests for save/read behavior**

In `ApiAuthResolverTest`, add tests that instantiate `ApiAuthConfigService` with mocked `ApiAuthConfigRepository` and verify:

- Saving group Bearer config encrypts `token` and returns preview containing `Bearer` without the raw token.
- Saving API Key config with `addTo=HEADER` returns preview containing `API Key header:X-API-Key`.
- Saving `NO_AUTH` stores no sensitive config and preview is `No Auth`.
- Reading a missing config returns a default `NO_AUTH` response.

- [ ] **Step B2.2: Run test to verify it fails**

Run: `mvn -Dtest=robot.agent.apicenter.service.ApiAuthResolverTest test` from `java-backend`.

Expected: fail because `ApiAuthConfigService` and related classes do not exist yet.

- [ ] **Step B2.3: Implement `ApiAuthCryptoService`**

Reuse the AES/GCM/NoPadding approach from `ApiHeaderCryptoService`, but name errors for auth config. Use system property `robot.api-center.auth-secret` and fallback to the existing default secret if absent. Always use `StandardCharsets.UTF_8`.

- [ ] **Step B2.4: Implement `ApiAuthConfigService`**

Expose methods:

```java
public Map<String, Object> saveAuthConfig(ApiAuthScopeType scopeType, Long scopeId, Map<String, Object> payload)
public Map<String, Object> getAuthConfig(ApiAuthScopeType scopeType, Long scopeId)
public EffectiveAuth resolveEffectiveAuth(Long groupId, Long apiId, String authMode)
```

`saveAuthConfig` validates `authType` and required fields, stores encrypted JSON, and returns a response with `authType`, `preview`, `configured`, and non-sensitive fields such as API Key `key/addTo`.

Use these required fields:

- `NO_AUTH`: no fields.
- `API_KEY`: `key`, `value`, `addTo` where `addTo` is `HEADER` or `QUERY`.
- `BEARER`: `token`.
- `BASIC`: `username`, `password`.
- `DIGEST`: `username`, `password`, optional `realm`, `nonce`, `algorithm`, `qop`.

Define an inner or separate immutable `EffectiveAuth` type with `authType`, decrypted `config`, and `preview`.

- [ ] **Step B2.5: Add controller endpoints**

Add routes to `ApiCenterController`:

```java
@GetMapping("/groups/{groupId}/auth-config")
@PutMapping("/groups/{groupId}/auth-config")
@GetMapping("/groups/{groupId}/items/{apiId}/auth-config")
@PutMapping("/groups/{groupId}/items/{apiId}/auth-config")
```

The item endpoint accepts `authMode`; `CUSTOM` saves an item config, `NONE` returns No Auth effective behavior, and `INHERIT` does not require custom fields.

- [ ] **Step B2.6: Run service tests**

Run: `mvn -Dtest=robot.agent.apicenter.service.ApiAuthResolverTest test` from `java-backend`.

Expected: all tests in this class pass.

### Task B3: Add Request Injection and Digest Support

**Files:**
- Create: `java-backend/src/main/java/robot/agent/apicenter/service/ApiAuthResolver.java`
- Create: `java-backend/src/main/java/robot/agent/apicenter/service/ApiDigestAuthService.java`
- Test: `java-backend/src/test/java/robot/agent/apicenter/service/ApiAuthResolverTest.java`
- Test: `java-backend/src/test/java/robot/agent/apicenter/service/ApiDigestAuthServiceTest.java`

- [ ] **Step B3.1: Write failing tests for header/query merge rules**

Add tests verifying:

- Bearer injects `Authorization` when no explicit Authorization header exists.
- Explicit `Authorization` header wins over Bearer generated header.
- API Key `HEADER` injects configured key when absent.
- Explicit same-name API Key header wins over generated value.
- API Key `QUERY` appends query param when absent.
- Existing URL query param wins and no duplicate API Key query param is added.
- API Key `QUERY` can append to URLs that still contain `{template}` variables without throwing URI parse errors.
- `NONE` effective auth injects nothing.

- [ ] **Step B3.2: Write failing Digest tests**

In `ApiDigestAuthServiceTest`, verify challenge parsing and header generation using a deterministic challenge:

```text
Digest realm="test", nonce="abc", qop="auth", opaque="xyz"
```

Assert the generated header starts with `Digest ` and contains `username="demo"`, `realm="test"`, `nonce="abc"`, `uri="/users"`, `qop=auth`, `nc=00000001`, `cnonce=`, and `response="`.

- [ ] **Step B3.3: Run tests to verify failure**

Run: `mvn -Dtest=robot.agent.apicenter.service.ApiAuthResolverTest,robot.agent.apicenter.service.ApiDigestAuthServiceTest test` from `java-backend`.

Expected: fail because resolver and Digest service are not implemented.

- [ ] **Step B3.4: Implement `ApiAuthResolver`**

Create a request-auth value object such as:

```java
public record AuthAppliedRequest(String url, Map<String, String> headers, ApiAuthType authType, Map<String, Object> config, String preview) {}
```

Implement:

```java
public AuthAppliedRequest apply(String url, Map<String, String> explicitHeaders, EffectiveAuth effectiveAuth)
```

Rules:

- Start from generated auth headers/query, then overlay explicit headers so explicit headers win.
- For API Key `QUERY`, only append if the URL does not already contain the same query key.
- Preserve existing URL variables already resolved by `ApiUrlTemplateResolver`.
- Do not throw for same-name Header or Query fields.

- [ ] **Step B3.5: Implement `ApiDigestAuthService`**

Implement parser for `WWW-Authenticate` Digest parameters and builder for Digest Authorization. Support `MD5` and `auth`; default to `MD5` and `auth` if server omits optional fields. Generate `cnonce` using `SecureRandom`. Use UTF-8 bytes for hashing input strings.

- [ ] **Step B3.6: Run resolver and Digest tests**

Run: `mvn -Dtest=robot.agent.apicenter.service.ApiAuthResolverTest,robot.agent.apicenter.service.ApiDigestAuthServiceTest test` from `java-backend`.

Expected: all tests pass.

### Task B4: Wire Auth into API Center Service and Runtime Resolver

**Files:**
- Modify: `java-backend/src/main/java/robot/agent/apicenter/service/ApiCenterService.java`
- Modify: `java-backend/src/main/java/robot/agent/apicenter/service/ApiRuntimeResolver.java`
- Modify: `java-backend/src/test/java/robot/agent/apicenter/service/ApiCenterServiceSaveGateTest.java`

- [ ] **Step B4.1: Write failing service tests**

Extend `ApiCenterServiceSaveGateTest` to verify:

- `validateDraft` accepts Headers containing `Authorization` when payload has `authMode=NONE`.
- `validateDraft` rejects incomplete custom auth config such as Bearer without token.
- `saveItem` persists `authMode=CUSTOM` and does not reject explicit `Authorization` Header.
- `saveItem` clears item-scope auth config when switching away from `CUSTOM`.
- `deleteItem` and `deleteGroup` clear related auth configs.
- `toItemDetail` includes `authMode` and an auth preview field.

- [ ] **Step B4.2: Run tests to verify failure**

Run: `mvn -Dtest=robot.agent.apicenter.service.ApiCenterServiceSaveGateTest test` from `java-backend`.

Expected: fail because service constructor and response fields are not yet wired.

- [ ] **Step B4.3: Update constructor dependencies**

Inject `ApiAuthConfigService`, `ApiAuthResolver`, and `ApiDigestAuthService` into `ApiCenterService`. Update tests to pass mocks or real instances as needed.

- [ ] **Step B4.4: Persist and return `authMode`**

In `applyItemPayload`, parse `authMode` defaulting to `INHERIT`; store on `ApiItem`. In `toItemSummary` and `toItemDetail`, include `authMode`, `authType`, and `authPreview`. If `CUSTOM` payload contains auth config, save item-scope config through `ApiAuthConfigService` after the item ID exists.

- [ ] **Step B4.5: Apply auth in request testing**

In `testDraft`, after URL resolution and header parsing, resolve effective auth from the draft payload. Apply auth through `ApiAuthResolver` before building `HttpRequest`. For Digest Auth, if the first response is `401` with Digest challenge, call `ApiDigestAuthService` and retry once with the generated Digest Authorization header.

- [ ] **Step B4.6: Apply auth in runtime resolver**

In `ApiRuntimeResolver`, include `auth_mode`, `auth_type`, and `auth_preview` in resolved config. Resolve effective headers with `ApiAuthResolver` so workflow execution receives the same headers and URL behavior as request testing.

- [ ] **Step B4.7: Run backend focused tests**

Run: `mvn -Dtest=robot.agent.apicenter.service.ApiCenterServiceSaveGateTest,robot.agent.apicenter.service.ApiAuthResolverTest,robot.agent.apicenter.service.ApiDigestAuthServiceTest test` from `java-backend`.

Expected: all focused backend auth tests pass.

---

## Frontend Tasks

### Task F1: Extend API Types and Client Functions

**Files:**
- Modify: `frontend/src/services/api.ts`
- Modify: `frontend/src/components/ApiCenterPanel.tsx`

- [ ] **Step F1.1: Add TypeScript auth types**

Add union types or string literal constants for:

```ts
type ApiAuthType = 'NO_AUTH' | 'API_KEY' | 'BEARER' | 'BASIC' | 'DIGEST'
type ApiAuthMode = 'INHERIT' | 'NONE' | 'CUSTOM'
type ApiKeyAddTo = 'HEADER' | 'QUERY'
```

Add `ApiAuthConfigPayload` with fields `authType`, `key`, `value`, `addTo`, `token`, `username`, `password`, `realm`, `nonce`, `algorithm`, `qop`.

Add `authMode`, `authType`, `authPreview`, and `authConfig` fields to API item payload/detail shapes used by `ApiCenterPanel`.

- [ ] **Step F1.2: Add client functions**

Add API functions for:

```ts
getApiGroupAuthConfig(groupId: number)
saveApiGroupAuthConfig(groupId: number, payload: ApiAuthConfigPayload)
getApiItemAuthConfig(groupId: number, apiId: number)
saveApiItemAuthConfig(groupId: number, apiId: number, payload: { authMode: ApiAuthMode; authConfig?: ApiAuthConfigPayload })
```

Use existing request helper style in `frontend/src/services/api.ts`.

- [ ] **Step F1.3: Run TypeScript check**

Run: `npm run build` or the repository's existing frontend typecheck command from `frontend`.

Expected: fail at this stage if UI has not consumed the new types; proceed to F2.

### Task F2: Add Auth Forms to API Group and API Item Dialogs

**Files:**
- Modify: `frontend/src/components/ApiCenterPanel.tsx`

- [ ] **Step F2.1: Add form state**

Extend group form with default auth config:

```ts
const defaultAuthConfig = { authType: 'NO_AUTH', addTo: 'HEADER', key: '', value: '', token: '', username: '', password: '', realm: '', nonce: '', algorithm: 'MD5', qop: 'auth' }
```

Extend API form with `authMode: 'INHERIT'` and `authConfig: defaultAuthConfig`.

- [ ] **Step F2.2: Add reusable auth editor renderer**

Inside `ApiCenterPanel.tsx`, add a small local component or render function `AuthConfigFields` that uses existing CSS classes only: `form-input`, `form-select`, existing grid classes, existing button classes. It must render:

- Auth type select.
- API Key: key, value password input, addTo select.
- Bearer: token password input.
- Basic: username input, password input.
- Digest: username, password, realm, nonce, algorithm, qop.
- No Auth: short muted description.

Do not add new high-saturation colors or new modal layout classes.

- [ ] **Step F2.3: Add API group auth center section**

In the group create/edit modal, add a section titled `鉴权中心`. Keep the same modal card, spacing, borders, rounded corners, and input styles as existing API Center fields.

- [ ] **Step F2.4: Add API item auth strategy section**

In the API item create/edit modal, add `鉴权策略` after request connection fields and before Headers. Include a select with:

- `继承API组鉴权`
- `不使用鉴权中心`
- `自定义鉴权`

For inherit, show group auth preview. For none, show text explaining Headers can still carry auth fields. For custom, render `AuthConfigFields`.

- [ ] **Step F2.5: Rename Headers copy without breaking tests**

Keep the visible `Headers` text used by existing tests, and add helper copy saying `可填写业务 Header，也可继续填写 Authorization、X-API-Key 等鉴权 Header。` Do not block the form when those fields exist.

### Task F3: Wire Save, Edit, and Request Test Payloads

**Files:**
- Modify: `frontend/src/components/ApiCenterPanel.tsx`
- Modify: `frontend/tests/e2e/api-center.spec.ts`

- [ ] **Step F3.1: Update payload serialization**

Update `buildApiPayload` so it includes `authMode` and, when `authMode === 'CUSTOM'`, includes `authConfig`. Keep Headers serialization unchanged.

- [ ] **Step F3.2: Update detail parsing**

When opening an existing API item, parse `detail.authMode`, `detail.authType`, `detail.authPreview`, and `detail.authConfig`. Default to `INHERIT` and `NO_AUTH` when fields are absent to preserve existing API items.

- [ ] **Step F3.3: Update group save flow**

When saving group auth inline, include `authConfig` in the group payload if the backend supports inline payload. If using the separate endpoint, call it after group save returns `id`. Prefer whichever is easiest to align with backend worker output; keep code isolated in small helper functions.

- [ ] **Step F3.4: Show effective auth in request test modal**

In request test modal, show `当前鉴权：<preview>` with no secret values. If no auth, show `当前鉴权：No Auth`.

- [ ] **Step F3.5: Add E2E coverage**

Extend `frontend/tests/e2e/api-center.spec.ts` mocks and tests to verify:

- Group modal shows `鉴权中心` and can select `Bearer Token`.
- Group auth center save calls the auth config endpoint with the selected auth type and secret field.
- API item modal defaults to `继承API组鉴权`.
- API item modal can select `不使用鉴权中心` while keeping `Authorization` Header and save succeeds.
- API item modal can select `自定义鉴权` with API Key Header and save payload includes `authMode: 'CUSTOM'` and `authConfig`.
- Request test modal shows current auth preview.

- [ ] **Step F3.6: Run frontend focused tests**

Run from `frontend`: `npx playwright test tests/e2e/api-center.spec.ts`.

Expected: API Center E2E tests pass or report only unrelated environment/browser startup failures.

---

## Final Verification

- [ ] **Step V1: Backend focused tests**

Run from `java-backend`:

```bash
mvn -Dtest=robot.agent.apicenter.service.ApiCenterServiceSaveGateTest,robot.agent.apicenter.service.ApiAuthResolverTest,robot.agent.apicenter.service.ApiDigestAuthServiceTest test
```

Expected: focused backend tests pass.

- [ ] **Step V2: Frontend build or typecheck**

Run from `frontend`:

```bash
npm run build
```

Expected: build succeeds.

- [ ] **Step V3: Frontend API Center E2E**

Run from `frontend`:

```bash
npx playwright test tests/e2e/api-center.spec.ts
```

Expected: API Center E2E passes. If browsers are missing, report the exact Playwright install error instead of claiming pass.

- [ ] **Step V4: Encoding check**

Run from repo root:

```powershell
$paths = @(
  'docs/superpowers/specs/2026-06-03-api-center-auth-design.md',
  'docs/superpowers/plans/2026-06-03-api-center-auth-implementation.md'
)
foreach ($path in $paths) {
  $bytes = [System.IO.File]::ReadAllBytes($path)
  $hasBom = $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF
  if ($hasBom) { throw "$path has UTF-8 BOM" }
}
```

Expected: no exception.
