# Capability Center Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add backend domain models, APIs, versioning, validation, testing, and runtime resolution for capability groups, capability items, and group snapshots so workflows can call only published capabilities from published snapshots.

**Architecture:** Reuse the existing Spring Boot workflow-version pattern for draft/publish/archive semantics, but introduce a separate capability domain with group snapshots and test records. Keep workflow definition storage JSON-based for the first phase, and add a resolver service that converts a workflow node’s capability reference into executable API / Skill / MCP runtime config for Python.

**Tech Stack:** Spring Boot, Spring MVC, JPA, Jackson, Maven, Python FastAPI runtime integration.

---

## File Structure

- Create `java-backend/src/main/java/robot/agent/model/CapabilityType.java`: enum for `API`, `SKILL`, `MCP`.
- Create `java-backend/src/main/java/robot/agent/model/CapabilityItemStatus.java`: enum for capability item lifecycle.
- Create `java-backend/src/main/java/robot/agent/model/CapabilityVersionStatus.java`: enum for capability version lifecycle.
- Create `java-backend/src/main/java/robot/agent/model/CapabilityGroupStatus.java`: enum for group lifecycle.
- Create `java-backend/src/main/java/robot/agent/model/AuthConfigStatus.java`: enum for auth config status.
- Create `java-backend/src/main/java/robot/agent/model/CapabilityGroup.java`: group aggregate root.
- Create `java-backend/src/main/java/robot/agent/model/CapabilityGroupSnapshot.java`: published snapshot entity.
- Create `java-backend/src/main/java/robot/agent/model/CapabilityItem.java`: capability metadata entity.
- Create `java-backend/src/main/java/robot/agent/model/CapabilityItemVersion.java`: draft / published capability version entity.
- Create `java-backend/src/main/java/robot/agent/model/AuthConfig.java`: encrypted auth config entity.
- Create `java-backend/src/main/java/robot/agent/model/CapabilityTestRecord.java`: persisted validation and test record entity.
- Create repositories under `java-backend/src/main/java/robot/agent/repository/` for all new entities.
- Create request DTOs under `java-backend/src/main/java/robot/agent/dto/request/` for group, capability, auth, publish, snapshot, and test operations.
- Create response DTOs under `java-backend/src/main/java/robot/agent/dto/response/` for groups, snapshots, capability items, versions, auth configs, and test records.
- Create `java-backend/src/main/java/robot/agent/service/CapabilityAuthService.java`: auth masking and encryption/decryption boundary.
- Create `java-backend/src/main/java/robot/agent/service/CapabilityValidationService.java`: capability config validation.
- Create `java-backend/src/main/java/robot/agent/service/CapabilityTestService.java`: API / Skill / MCP test orchestration.
- Create `java-backend/src/main/java/robot/agent/service/CapabilityService.java`: group, capability, version, and publish orchestration.
- Create `java-backend/src/main/java/robot/agent/service/CapabilityRuntimeResolver.java`: resolve workflow node capability reference into runtime tool config.
- Create `java-backend/src/main/java/robot/agent/controller/CapabilityController.java`: capability center REST API.
- Modify `java-backend/src/main/java/robot/agent/service/WorkflowService.java`: validate capability call nodes against snapshots.
- Modify `java-backend/src/main/java/robot/agent/dto/request/ExecuteRequest.java`: add optional capability catalog payload when needed.
- Modify `java-backend/src/main/java/robot/agent/service/ExecutionService.java` or the workflow execution assembly path that builds runtime payloads for Python.
- Modify `python-ai/src/nodes/tool.py`: support resolved capability references and normalized runtime config.
- Create backend tests under `java-backend/src/test/java/robot/agent/service/` and `java-backend/src/test/java/robot/agent/controller/`.

## Task 1: Define Backend Domain Model

**Files:**
- Create: `java-backend/src/main/java/robot/agent/model/CapabilityType.java`
- Create: `java-backend/src/main/java/robot/agent/model/CapabilityItemStatus.java`
- Create: `java-backend/src/main/java/robot/agent/model/CapabilityVersionStatus.java`
- Create: `java-backend/src/main/java/robot/agent/model/CapabilityGroupStatus.java`
- Create: `java-backend/src/main/java/robot/agent/model/AuthConfigStatus.java`
- Create: `java-backend/src/main/java/robot/agent/model/CapabilityGroup.java`
- Create: `java-backend/src/main/java/robot/agent/model/CapabilityGroupSnapshot.java`
- Create: `java-backend/src/main/java/robot/agent/model/CapabilityItem.java`
- Create: `java-backend/src/main/java/robot/agent/model/CapabilityItemVersion.java`
- Create: `java-backend/src/main/java/robot/agent/model/AuthConfig.java`
- Create: `java-backend/src/main/java/robot/agent/model/CapabilityTestRecord.java`

- [ ] **Step 1: Add lifecycle enums**

Create enums like:

```java
package robot.agent.model;

public enum CapabilityType {
    API,
    SKILL,
    MCP
}
```

and:

```java
package robot.agent.model;

public enum CapabilityVersionStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED
}
```

Mirror this style for item, group, and auth statuses.

- [ ] **Step 2: Add `CapabilityGroup` entity**

Create the entity skeleton:

```java
@Entity
@Table(name = "capability_group", uniqueConstraints = {
        @UniqueConstraint(name = "uk_capability_group_code", columnNames = {"group_code"})
})
public class CapabilityGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_code", length = 64, nullable = false)
    private String groupCode;

    @Column(name = "group_name", length = 128, nullable = false)
    private String groupName;

    @Column(name = "domain_code", length = 64, nullable = false)
    private String domainCode;
}
```

Add `description`, `defaultAuthConfigId`, `status`, `createdBy`, `createdAt`, and `updatedAt`.

- [ ] **Step 3: Add `CapabilityItem` and `CapabilityItemVersion` entities**

Use this shape:

```java
@Entity
@Table(name = "capability_item", uniqueConstraints = {
        @UniqueConstraint(name = "uk_capability_item_code", columnNames = {"capability_code"})
})
public class CapabilityItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_code", length = 64, nullable = false)
    private String groupCode;

    @Column(name = "capability_code", length = 64, nullable = false)
    private String capabilityCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "capability_type", length = 16, nullable = false)
    private CapabilityType capabilityType;
}
```

and:

```java
@Entity
@Table(name = "capability_item_version", uniqueConstraints = {
        @UniqueConstraint(name = "uk_capability_item_version", columnNames = {"capability_code", "version"})
})
public class CapabilityItemVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "capability_code", length = 64, nullable = false)
    private String capabilityCode;

    @Column(name = "version", length = 32, nullable = false)
    private String version;

    @Lob
    @Column(name = "definition_json", nullable = false)
    private String definitionJson;
}
```

Add `inputSchema`, `outputSchema`, `authBinding`, `environmentBinding`, `status`, `createdBy`, `createdAt`, and `publishedAt`.

- [ ] **Step 4: Add snapshot, auth, and test record entities**

Implement:

```java
@Entity
@Table(name = "capability_group_snapshot", uniqueConstraints = {
        @UniqueConstraint(name = "uk_group_snapshot", columnNames = {"group_code", "snapshot_version"})
})
public class CapabilityGroupSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_code", length = 64, nullable = false)
    private String groupCode;

    @Column(name = "snapshot_version", length = 32, nullable = false)
    private String snapshotVersion;

    @Lob
    @Column(name = "snapshot_payload", nullable = false)
    private String snapshotPayload;
}
```

Also add:

```java
@Entity
@Table(name = "auth_config")
public class AuthConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "auth_type", length = 32, nullable = false)
    private String authType;

    @Lob
    @Column(name = "config_encrypted", nullable = false)
    private String configEncrypted;

    @Column(name = "masked_preview", length = 255)
    private String maskedPreview;
}
```

and a `CapabilityTestRecord` entity with `testType`, `requestPayload`, `responsePayload`, `success`, `errorMessage`, `durationMs`, `createdAt`, and `createdBy`.

- [ ] **Step 5: Run backend compile**

Run from repo root: `mvn -pl java-backend -DskipTests compile`

Expected: model classes compile cleanly and JPA imports resolve.

## Task 2: Add Repositories and DTO Contracts

**Files:**
- Create: `java-backend/src/main/java/robot/agent/repository/CapabilityGroupRepository.java`
- Create: `java-backend/src/main/java/robot/agent/repository/CapabilityGroupSnapshotRepository.java`
- Create: `java-backend/src/main/java/robot/agent/repository/CapabilityItemRepository.java`
- Create: `java-backend/src/main/java/robot/agent/repository/CapabilityItemVersionRepository.java`
- Create: `java-backend/src/main/java/robot/agent/repository/AuthConfigRepository.java`
- Create: `java-backend/src/main/java/robot/agent/repository/CapabilityTestRecordRepository.java`
- Create request DTOs under `java-backend/src/main/java/robot/agent/dto/request/`
- Create response DTOs under `java-backend/src/main/java/robot/agent/dto/response/`

- [ ] **Step 1: Add repositories**

Create repository interfaces like:

```java
public interface CapabilityGroupRepository extends JpaRepository<CapabilityGroup, Long> {
    Optional<CapabilityGroup> findByGroupCode(String groupCode);
    List<CapabilityGroup> findAllByOrderByUpdatedAtDesc();
}
```

and:

```java
public interface CapabilityItemVersionRepository extends JpaRepository<CapabilityItemVersion, Long> {
    Optional<CapabilityItemVersion> findByCapabilityCodeAndVersion(String capabilityCode, String version);
    List<CapabilityItemVersion> findByCapabilityCodeAndStatusNotOrderByCreatedAtDesc(
            String capabilityCode,
            CapabilityVersionStatus status
    );
}
```

- [ ] **Step 2: Add request DTOs**

Create request classes including:

```java
public class UpsertCapabilityGroupRequest {
    private String groupCode;
    private String groupName;
    private String domainCode;
    private String description;
    private Long defaultAuthConfigId;
}
```

```java
public class UpsertCapabilityItemVersionRequest {
    private String capabilityName;
    private String version;
    private String definitionJson;
    private String inputSchema;
    private String outputSchema;
    private String authBinding;
    private String environmentBinding;
}
```

Also add DTOs for publish snapshot, validate capability, test capability, and auth config upsert.

- [ ] **Step 3: Add response DTOs**

Create response classes that mirror current workflow DTO style, for example:

```java
public class CapabilityVersionResponse {
    private Long id;
    private String capabilityCode;
    private String version;
    private String status;
    private String definitionJson;
    private String inputSchema;
    private String outputSchema;
    private String publishedAt;
}
```

Provide `fromEntity(...)` static mappers similar to `WorkflowVersionResponse`.

- [ ] **Step 4: Run compile**

Run from repo root: `mvn -pl java-backend -DskipTests compile`

Expected: repository and DTO layer compile with no missing imports.

## Task 3: Implement Auth, Validation, and Test Services

**Files:**
- Create: `java-backend/src/main/java/robot/agent/service/CapabilityAuthService.java`
- Create: `java-backend/src/main/java/robot/agent/service/CapabilityValidationService.java`
- Create: `java-backend/src/main/java/robot/agent/service/CapabilityTestService.java`

- [ ] **Step 1: Implement auth masking and encryption boundary**

Create a service with methods like:

```java
@Service
public class CapabilityAuthService {
    public String encryptConfig(String rawJson) {
        return Base64.getEncoder().encodeToString(rawJson.getBytes(StandardCharsets.UTF_8));
    }

    public String decryptConfig(String encrypted) {
        return new String(Base64.getDecoder().decode(encrypted), StandardCharsets.UTF_8);
    }

    public String maskPreview(String authType, String rawJson) {
        return authType + ": configured";
    }
}
```

Use this placeholder implementation first, but keep the interface narrow so encryption can be replaced later.

- [ ] **Step 2: Implement capability validation**

Create validation methods:

```java
public List<Map<String, Object>> validateCapabilityDefinition(CapabilityType type, Map<String, Object> definition) {
    List<Map<String, Object>> issues = new ArrayList<>();
    if (type == CapabilityType.API && stringValue(definition.get("url")) == null) {
        issues.add(issue("definition.url", "API 调用缺少 url"));
    }
    return issues;
}
```

Add separate checks for:

- API: `url`, `method`
- Skill: `skill_name`, `executor_type`, `input_schema`
- MCP: `server_url`, `protocol`

- [ ] **Step 3: Implement test orchestration**

Create methods:

```java
public CapabilityTestRecord runApiTest(Map<String, Object> definition, Map<String, Object> payload) { ... }
public CapabilityTestRecord runSkillValidation(Map<String, Object> definition, Map<String, Object> payload) { ... }
public CapabilityTestRecord runMcpDiscovery(Map<String, Object> definition) { ... }
```

For first phase:

- API uses `RestTemplate` or `WebClient`
- Skill validation can be synchronous metadata + sample-run simulation
- MCP test can start with connection + metadata discovery stub if no full server exists in repo

- [ ] **Step 4: Persist test records**

Save each test result with:

```java
CapabilityTestRecord record = new CapabilityTestRecord();
record.setCapabilityCode(capabilityCode);
record.setCapabilityVersion(version);
record.setTestType(testType);
record.setSuccess(success);
record.setRequestPayload(requestJson);
record.setResponsePayload(responseJson);
record.setErrorMessage(errorMessage);
record.setDurationMs(durationMs);
```

- [ ] **Step 5: Run service tests or compile**

Run from repo root: `mvn -pl java-backend -DskipTests compile`

Expected: service layer compiles.

## Task 4: Implement Capability Domain Service and Controller

**Files:**
- Create: `java-backend/src/main/java/robot/agent/service/CapabilityService.java`
- Create: `java-backend/src/main/java/robot/agent/controller/CapabilityController.java`

- [ ] **Step 1: Implement group CRUD service**

Add methods like:

```java
public CapabilityGroupResponse saveGroup(String userId, UpsertCapabilityGroupRequest request) {
    CapabilityGroup group = capabilityGroupRepository.findByGroupCode(request.getGroupCode())
            .orElseGet(CapabilityGroup::new);
    group.setGroupCode(request.getGroupCode());
    group.setGroupName(request.getGroupName());
    group.setDomainCode(request.getDomainCode());
    group.setDescription(request.getDescription());
    group.setDefaultAuthConfigId(request.getDefaultAuthConfigId());
    group.setStatus(CapabilityGroupStatus.DRAFT);
    return CapabilityGroupResponse.fromEntity(capabilityGroupRepository.save(group));
}
```

- [ ] **Step 2: Implement capability item draft save and publish**

Add methods like:

```java
public CapabilityVersionResponse saveCapabilityDraft(String userId, String groupCode, String capabilityCode, UpsertCapabilityItemVersionRequest request) { ... }

public CapabilityVersionResponse publishCapabilityVersion(String userId, String capabilityCode, String version) { ... }
```

Mirror the existing `WorkflowService.saveWorkflowDraft` and `publishWorkflow` structure.

- [ ] **Step 3: Implement snapshot publish**

Add a publish method:

```java
public CapabilityGroupSnapshotResponse publishGroupSnapshot(String userId, String groupCode, PublishCapabilityGroupSnapshotRequest request) {
    Map<String, Object> snapshot = Map.of(
            "group_code", groupCode,
            "capabilities", publishedCapabilities
    );
}
```

The snapshot payload must include each capability’s published version and resolved type.

- [ ] **Step 4: Add controller endpoints**

Add endpoints such as:

```java
@RestController
@RequestMapping("/api/capabilities")
public class CapabilityController {
    @GetMapping("/groups")
    public ResponseEntity<List<CapabilityGroupResponse>> listGroups() { ... }

    @PostMapping("/groups/{groupCode}/drafts")
    public ResponseEntity<CapabilityVersionResponse> saveCapabilityDraft(...) { ... }
}
```

Minimum endpoint set:

- group list / create / update / delete
- capability list by group
- capability draft save
- capability version list
- capability publish
- group snapshot publish
- group snapshot list
- auth config save
- validate and test endpoints

- [ ] **Step 5: Run backend compile**

Run from repo root: `mvn -pl java-backend -DskipTests compile`

Expected: controller wiring compiles and Spring dependency injection resolves.

## Task 5: Integrate Workflow Validation and Runtime Resolution

**Files:**
- Create: `java-backend/src/main/java/robot/agent/service/CapabilityRuntimeResolver.java`
- Modify: `java-backend/src/main/java/robot/agent/service/WorkflowService.java`
- Modify: `java-backend/src/main/java/robot/agent/service/ExecutionService.java`
- Modify: `java-backend/src/main/java/robot/agent/dto/request/ExecuteRequest.java`
- Modify: `python-ai/src/nodes/tool.py`

- [ ] **Step 1: Define capability-call node config contract**

Use this normalized workflow node config:

```json
{
  "invoke_type": "capability",
  "group_code": "payment_domain",
  "group_snapshot_version": "v20260425153000",
  "capability_type": "API",
  "capability_code": "payment_refund_apply",
  "capability_version": "v20260425142000",
  "payload_mapping": {
    "orderId": "execution.order_id"
  }
}
```

- [ ] **Step 2: Extend workflow validation**

In `WorkflowService.validateToolNode`, add:

```java
case "capability" -> {
    if (stringValue(nodeConfig.get("group_code")) == null) {
        issues.add(issue(nodeId, "config.group_code", "能力调用缺少 group_code"));
    }
    if (stringValue(nodeConfig.get("group_snapshot_version")) == null) {
        issues.add(issue(nodeId, "config.group_snapshot_version", "能力调用缺少 group_snapshot_version"));
    }
    if (stringValue(nodeConfig.get("capability_code")) == null) {
        issues.add(issue(nodeId, "config.capability_code", "能力调用缺少 capability_code"));
    }
}
```

- [ ] **Step 3: Add runtime resolver**

Implement a resolver:

```java
public Map<String, Object> resolveCapabilityToolConfig(String groupCode, String snapshotVersion, String capabilityCode) {
    CapabilityGroupSnapshot snapshot = snapshotRepository.findByGroupCodeAndSnapshotVersion(groupCode, snapshotVersion)
            .orElseThrow(() -> new RuntimeException("Capability snapshot not found"));
    return extractCapabilityDefinition(snapshot.getSnapshotPayload(), capabilityCode);
}
```

Return a normalized runtime config with `invoke_type` set back to `api`, `skill`, or `mcp`.

- [ ] **Step 4: Pass resolved config into execution runtime**

Where workflow execution currently sends tool config to Python, transform capability reference nodes into resolved tool configs:

```java
Map<String, Object> nodeConfig = ...;
if ("capability".equals(nodeConfig.get("invoke_type"))) {
    nodeConfig = capabilityRuntimeResolver.resolveNodeConfig(nodeConfig);
}
```

- [ ] **Step 5: Extend Python tool node only if needed**

In `python-ai/src/nodes/tool.py`, keep compatibility with resolved configs and optionally add:

```python
if self.invoke_type == "capability":
    raise RuntimeError("capability invoke_type must be resolved by Java before execution")
```

This prevents silent runtime drift.

- [ ] **Step 6: Run backend and Python targeted tests**

Run from repo root:

```powershell
mvn -pl java-backend test
pytest python-ai/tests/test_nodes/test_tool.py -q
```

Expected: backend tests pass; Python tool-node test remains green or only fails on clearly related assertions.

## Task 6: Add Backend Tests

**Files:**
- Create: `java-backend/src/test/java/robot/agent/service/CapabilityServiceTest.java`
- Create: `java-backend/src/test/java/robot/agent/service/CapabilityValidationServiceTest.java`
- Create: `java-backend/src/test/java/robot/agent/controller/CapabilityControllerTest.java`
- Modify: `java-backend/src/test/java/robot/agent/service/WorkflowServiceTest.java`

- [ ] **Step 1: Add validation service tests**

Write tests like:

```java
@Test
void validateApiDefinition_requiresUrl() {
    List<Map<String, Object>> issues = service.validateCapabilityDefinition(
            CapabilityType.API,
            Map.of("method", "POST")
    );

    assertThat(issues).isNotEmpty();
}
```

- [ ] **Step 2: Add capability publish and snapshot tests**

Write tests for:

- capability draft save
- capability publish
- snapshot publish only with published versions
- latest failed test blocks snapshot when configured

Example:

```java
@Test
void publishGroupSnapshot_rejectsUnpublishedCapabilityVersion() {
    assertThatThrownBy(() -> capabilityService.publishGroupSnapshot("demo-admin", "payment_domain", request))
            .hasMessageContaining("published");
}
```

- [ ] **Step 3: Add workflow validation test for capability invoke type**

Extend `WorkflowServiceTest` with a definition snippet:

```java
Map<String, Object> toolNode = Map.of(
        "type", "tool",
        "config", Map.of("invoke_type", "capability")
);
```

Assert the validation issues include missing `group_code`, `group_snapshot_version`, and `capability_code`.

- [ ] **Step 4: Run backend tests**

Run from repo root: `mvn -pl java-backend test`

Expected: capability service/controller/validation tests pass.

## Task 7: Final Verification and Scope Check

**Files:**
- Verify all backend and Python files touched by this plan.

- [ ] **Step 1: Run backend full tests**

Run from repo root: `mvn test`

Expected: Maven exits 0. If unrelated tests fail, capture exact failures and stop there.

- [ ] **Step 2: Run targeted Python tests**

Run from repo root:

```powershell
pytest python-ai/tests/test_nodes/test_tool.py -q
pytest python-ai/tests/test_core/test_registry.py -q
```

Expected: no regressions in tool execution and registry handling.

- [ ] **Step 3: Inspect backend diff**

Run:

```powershell
git diff -- java-backend python-ai docs/superpowers/specs/2026-04-25-capability-center-design.md docs/superpowers/plans/2026-04-25-capability-center-backend-implementation.md
```

Expected: diff is limited to capability center backend/runtime work.
