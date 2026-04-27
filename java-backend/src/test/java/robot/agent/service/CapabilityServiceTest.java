package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import robot.agent.model.CapabilityAuthConfig;
import robot.agent.model.CapabilityGroup;
import robot.agent.model.CapabilityGroupSnapshot;
import robot.agent.model.CapabilityItem;
import robot.agent.model.CapabilityType;
import robot.agent.repository.CapabilityAuthConfigRepository;
import robot.agent.repository.CapabilityGroupRepository;
import robot.agent.repository.CapabilityGroupSnapshotRepository;
import robot.agent.repository.CapabilityItemRepository;
import robot.agent.repository.CapabilityTestRecordRepository;

import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void getCapabilityGroupsReturnsIdNameDescriptionWithoutExposingCodes() {
        CapabilityGroupRepository groupRepository = mock(CapabilityGroupRepository.class);
        CapabilityItemRepository itemRepository = mock(CapabilityItemRepository.class);
        CapabilityGroupSnapshotRepository snapshotRepository = mock(CapabilityGroupSnapshotRepository.class);
        CapabilityAuthConfigRepository authConfigRepository = mock(CapabilityAuthConfigRepository.class);
        CapabilityTestRecordRepository testRecordRepository = mock(CapabilityTestRecordRepository.class);

        CapabilityGroup group = new CapabilityGroup();
        group.setId(1L);
        group.setGroupCode("payment_domain");
        group.setGroupName("支付能力组");
        group.setDescription("支付相关能力");
        group.setStatus("PUBLISHED");
        group.setUpdatedAt(LocalDateTime.parse("2026-04-25T12:00:00"));

        CapabilityGroupSnapshot snapshot = new CapabilityGroupSnapshot();
        snapshot.setId(10L);
        snapshot.setGroupCode("payment_domain");
        snapshot.setSnapshotVersion("v20260425120000");
        snapshot.setStatus("PUBLISHED");
        snapshot.setPublishedAt(LocalDateTime.parse("2026-04-25T12:05:00"));

        when(groupRepository.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of(group));
        when(itemRepository.countByGroupCode("payment_domain")).thenReturn(2L);
        when(snapshotRepository.findFirstByGroupCodeOrderByPublishedAtDesc("payment_domain")).thenReturn(snapshot);

        CapabilityService capabilityService = new CapabilityService(
                groupRepository,
                itemRepository,
                snapshotRepository,
                authConfigRepository,
                testRecordRepository,
                objectMapper
        );

        List<Map<String, Object>> groups = capabilityService.getCapabilityGroups();

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0))
                .containsEntry("id", 1L)
                .containsEntry("groupName", "支付能力组")
                .containsEntry("description", "支付相关能力")
                .containsEntry("capabilityCount", 2L)
                .containsEntry("latestSnapshotVersion", "v20260425120000");
        assertThat(groups.get(0)).doesNotContainKeys("groupCode", "domainCode", "defaultAuthConfigId");
    }

    @Test
    void validateCapabilityDraftRequiresApiUrlAndMethod() {
        CapabilityGroupRepository groupRepository = mock(CapabilityGroupRepository.class);
        CapabilityItemRepository itemRepository = mock(CapabilityItemRepository.class);
        CapabilityGroupSnapshotRepository snapshotRepository = mock(CapabilityGroupSnapshotRepository.class);
        CapabilityAuthConfigRepository authConfigRepository = mock(CapabilityAuthConfigRepository.class);
        CapabilityTestRecordRepository testRecordRepository = mock(CapabilityTestRecordRepository.class);

        CapabilityGroup group = new CapabilityGroup();
        group.setId(1L);
        group.setGroupCode("payment_domain");
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));

        CapabilityService capabilityService = new CapabilityService(
                groupRepository,
                itemRepository,
                snapshotRepository,
                authConfigRepository,
                testRecordRepository,
                objectMapper
        );

        Map<String, Object> result = capabilityService.validateCapabilityDraft(1L, Map.of(
                "capabilityType", CapabilityType.API.name(),
                "definitionJson", "{ \"headers\": {} }"
        ));

        assertThat(result).containsEntry("valid", false);
        assertThat(result.get("issues")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(Map.class))
                .extracting(issue -> issue.get("field"))
                .contains("definition.url", "definition.method");
    }

    @Test
    void validateCapabilityDraftRejectsUnsupportedSkillAndMcpTypes() {
        CapabilityGroupRepository groupRepository = mock(CapabilityGroupRepository.class);
        CapabilityItemRepository itemRepository = mock(CapabilityItemRepository.class);
        CapabilityGroupSnapshotRepository snapshotRepository = mock(CapabilityGroupSnapshotRepository.class);
        CapabilityAuthConfigRepository authConfigRepository = mock(CapabilityAuthConfigRepository.class);
        CapabilityTestRecordRepository testRecordRepository = mock(CapabilityTestRecordRepository.class);

        CapabilityGroup group = new CapabilityGroup();
        group.setId(1L);
        group.setGroupCode("payment_domain");
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));

        CapabilityService capabilityService = new CapabilityService(
                groupRepository,
                itemRepository,
                snapshotRepository,
                authConfigRepository,
                testRecordRepository,
                objectMapper
        );

        Map<String, Object> skillResult = capabilityService.validateCapabilityDraft(1L, Map.of(
                "capabilityType", CapabilityType.SKILL.name(),
                "definitionJson", "{ \"skill_name\": \"health.check\", \"executor_type\": \"sync\" }"
        ));
        Map<String, Object> mcpResult = capabilityService.validateCapabilityDraft(1L, Map.of(
                "capabilityType", CapabilityType.MCP.name(),
                "definitionJson", "{ \"server_url\": \"http://127.0.0.1:3001\", \"protocol\": \"sse\" }"
        ));

        assertThat(skillResult).containsEntry("valid", false);
        assertThat(skillResult.get("issues")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(Map.class))
                .extracting(issue -> issue.get("field"))
                .contains("capabilityType");

        assertThat(mcpResult).containsEntry("valid", false);
        assertThat(mcpResult.get("issues")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(Map.class))
                .extracting(issue -> issue.get("field"))
                .contains("capabilityType");
    }

    @Test
    void getCapabilityGroupDetailCollectionsAreScopedByGroupId() {
        CapabilityGroupRepository groupRepository = mock(CapabilityGroupRepository.class);
        CapabilityItemRepository itemRepository = mock(CapabilityItemRepository.class);
        CapabilityGroupSnapshotRepository snapshotRepository = mock(CapabilityGroupSnapshotRepository.class);
        CapabilityAuthConfigRepository authConfigRepository = mock(CapabilityAuthConfigRepository.class);
        CapabilityTestRecordRepository testRecordRepository = mock(CapabilityTestRecordRepository.class);

        CapabilityItem item = new CapabilityItem();
        item.setId(11L);
        item.setGroupCode("payment_domain");
        item.setCapabilityCode("payment_refund_apply");
        item.setCapabilityName("退款申请");
        item.setCapabilityType(CapabilityType.API);
        item.setStatus("DRAFT");
        item.setDraftVersion("draft");

        CapabilityAuthConfig authConfig = new CapabilityAuthConfig();
        authConfig.setId(21L);
        authConfig.setGroupCode("payment_domain");
        authConfig.setAuthName("支付网关密钥");
        authConfig.setAuthType("API_KEY");
        authConfig.setStatus("ACTIVE");

        CapabilityGroupSnapshot snapshot = new CapabilityGroupSnapshot();
        snapshot.setId(31L);
        snapshot.setGroupCode("payment_domain");
        snapshot.setSnapshotVersion("v20260425121000");
        snapshot.setStatus("PUBLISHED");

        CapabilityGroup group = new CapabilityGroup();
        group.setId(1L);
        group.setGroupCode("payment_domain");
        group.setGroupName("支付能力组");
        group.setStatus("PUBLISHED");

        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
        when(itemRepository.findByGroupCodeOrderByUpdatedAtDesc("payment_domain")).thenReturn(List.of(item));
        when(authConfigRepository.findByGroupCodeOrderByUpdatedAtDesc("payment_domain")).thenReturn(List.of(authConfig));
        when(snapshotRepository.findByGroupCodeOrderByPublishedAtDesc("payment_domain")).thenReturn(List.of(snapshot));

        CapabilityService capabilityService = new CapabilityService(
                groupRepository,
                itemRepository,
                snapshotRepository,
                authConfigRepository,
                testRecordRepository,
                objectMapper
        );

        assertThat(capabilityService.getCapabilitiesByGroup(1L)).hasSize(1);
        assertThat(capabilityService.getCapabilityAuthConfigs(1L)).hasSize(1);
        assertThat(capabilityService.getCapabilityGroupSnapshots(1L)).hasSize(1);
    }

    @Test
    void saveCapabilityDraftRejectsMissingAuthConfigId() {
        CapabilityGroupRepository groupRepository = mock(CapabilityGroupRepository.class);
        CapabilityItemRepository itemRepository = mock(CapabilityItemRepository.class);
        CapabilityGroupSnapshotRepository snapshotRepository = mock(CapabilityGroupSnapshotRepository.class);
        CapabilityAuthConfigRepository authConfigRepository = mock(CapabilityAuthConfigRepository.class);
        CapabilityTestRecordRepository testRecordRepository = mock(CapabilityTestRecordRepository.class);

        CapabilityGroup group = new CapabilityGroup();
        group.setId(1L);
        group.setGroupCode("payment_domain");
        group.setGroupName("Payment");

        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));

        CapabilityService capabilityService = new CapabilityService(
                groupRepository,
                itemRepository,
                snapshotRepository,
                authConfigRepository,
                testRecordRepository,
                objectMapper
        );

        assertThatThrownBy(() -> capabilityService.saveCapabilityDraft(1L, null, Map.of(
                "capabilityName", "Health Check",
                "capabilityType", CapabilityType.API.name(),
                "definitionJson", "{\"url\":\"http://127.0.0.1:8080/health\",\"method\":\"GET\"}"
        ))).hasMessageContaining("必须绑定");
    }

    @Test
    void saveCapabilityGroupBackfillsLegacyDomainCodeWhenUiPayloadOmitsIt() {
        CapabilityGroupRepository groupRepository = mock(CapabilityGroupRepository.class);
        CapabilityItemRepository itemRepository = mock(CapabilityItemRepository.class);
        CapabilityGroupSnapshotRepository snapshotRepository = mock(CapabilityGroupSnapshotRepository.class);
        CapabilityAuthConfigRepository authConfigRepository = mock(CapabilityAuthConfigRepository.class);
        CapabilityTestRecordRepository testRecordRepository = mock(CapabilityTestRecordRepository.class);

        AtomicReference<CapabilityGroup> savedGroup = new AtomicReference<>();
        when(groupRepository.save(any(CapabilityGroup.class))).thenAnswer(invocation -> {
            CapabilityGroup group = invocation.getArgument(0);
            savedGroup.set(group);
            group.setId(1L);
            return group;
        });

        CapabilityService capabilityService = new CapabilityService(
                groupRepository,
                itemRepository,
                snapshotRepository,
                authConfigRepository,
                testRecordRepository,
                objectMapper
        );

        Map<String, Object> result = capabilityService.saveCapabilityGroup(Map.of(
                "groupName", "Payment",
                "description", "Payment APIs"
        ), null);

        assertThat(result).containsEntry("groupName", "Payment");
        assertThat(savedGroup.get()).isNotNull();
        assertThat(savedGroup.get().getGroupCode()).isNotBlank();
        assertThat(savedGroup.get().getDomainCode()).isEqualTo(savedGroup.get().getGroupCode());
    }

    @Test
    void saveCapabilityAuthConfigUpdatePreservesExistingConfigWhenEditPayloadOmitsConfig() {
        CapabilityGroupRepository groupRepository = mock(CapabilityGroupRepository.class);
        CapabilityItemRepository itemRepository = mock(CapabilityItemRepository.class);
        CapabilityGroupSnapshotRepository snapshotRepository = mock(CapabilityGroupSnapshotRepository.class);
        CapabilityAuthConfigRepository authConfigRepository = mock(CapabilityAuthConfigRepository.class);
        CapabilityTestRecordRepository testRecordRepository = mock(CapabilityTestRecordRepository.class);

        CapabilityGroup group = new CapabilityGroup();
        group.setId(1L);
        group.setGroupCode("payment_domain");
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));

        CapabilityAuthConfig existing = new CapabilityAuthConfig();
        existing.setId(21L);
        existing.setGroupCode("payment_domain");
        existing.setAuthName("Gateway Token");
        existing.setAuthType("JWT");
        existing.setMaskedPreview("已配置 / 已脱敏");
        existing.setConfigJson("{\"token\":\"demo-token\"}");
        when(authConfigRepository.findById(21L)).thenReturn(Optional.of(existing));
        when(authConfigRepository.save(any(CapabilityAuthConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CapabilityService capabilityService = new CapabilityService(
                groupRepository,
                itemRepository,
                snapshotRepository,
                authConfigRepository,
                testRecordRepository,
                objectMapper
        );

        Map<String, Object> result = capabilityService.saveCapabilityAuthConfig(1L, 21L, Map.of(
                "authName", "Gateway Token Updated",
                "authType", "JWT"
        ));

        assertThat(result).containsEntry("authName", "Gateway Token Updated");
        assertThat(existing.getConfigJson()).isEqualTo("{\"token\":\"demo-token\"}");
        assertThat(existing.getMaskedPreview()).isEqualTo("已配置 / 已脱敏");
    }

    @Test
    void getCapabilityVersionsIncludesAuthConfigIdForEditWriteBack() {
        CapabilityGroupRepository groupRepository = mock(CapabilityGroupRepository.class);
        CapabilityItemRepository itemRepository = mock(CapabilityItemRepository.class);
        CapabilityGroupSnapshotRepository snapshotRepository = mock(CapabilityGroupSnapshotRepository.class);
        CapabilityAuthConfigRepository authConfigRepository = mock(CapabilityAuthConfigRepository.class);
        CapabilityTestRecordRepository testRecordRepository = mock(CapabilityTestRecordRepository.class);

        CapabilityGroup group = new CapabilityGroup();
        group.setId(1L);
        group.setGroupCode("payment_domain");

        CapabilityItem item = new CapabilityItem();
        item.setId(11L);
        item.setGroupCode("payment_domain");
        item.setCapabilityCode("service_health_check");
        item.setCapabilityName("Service Health Check");
        item.setCapabilityType(CapabilityType.API);
        item.setDraftVersion("draft");
        item.setAuthConfigId(21L);

        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
        when(itemRepository.findByGroupCodeAndCapabilityCode("payment_domain", "service_health_check")).thenReturn(Optional.of(item));

        CapabilityService capabilityService = new CapabilityService(
                groupRepository,
                itemRepository,
                snapshotRepository,
                authConfigRepository,
                testRecordRepository,
                objectMapper
        );

        List<Map<String, Object>> versions = capabilityService.getCapabilityVersions(1L, "service_health_check");

        assertThat(versions).hasSize(1);
        assertThat(versions.get(0)).containsEntry("authConfigId", 21L);
    }

    @Test
    void testCapabilityForApiPerformsRealHttpRequestWithConfiguredAuth() throws Exception {
        CapabilityGroupRepository groupRepository = mock(CapabilityGroupRepository.class);
        CapabilityItemRepository itemRepository = mock(CapabilityItemRepository.class);
        CapabilityGroupSnapshotRepository snapshotRepository = mock(CapabilityGroupSnapshotRepository.class);
        CapabilityAuthConfigRepository authConfigRepository = mock(CapabilityAuthConfigRepository.class);
        CapabilityTestRecordRepository testRecordRepository = mock(CapabilityTestRecordRepository.class);

        CapabilityGroup group = new CapabilityGroup();
        group.setId(1L);
        group.setGroupCode("payment_domain");
        group.setGroupName("Payment");

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/health", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer demo-token".equals(authorization)) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            byte[] body = "{\"status\":\"UP\"}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            CapabilityItem item = new CapabilityItem();
            item.setGroupCode("payment_domain");
            item.setCapabilityCode("service_health_check");
            item.setCapabilityName("Service Health Check");
            item.setCapabilityType(CapabilityType.API);
            item.setDefinitionJson(objectMapper.writeValueAsString(Map.of(
                    "url", "http://127.0.0.1:" + server.getAddress().getPort() + "/health",
                    "method", "GET",
                    "headers", Map.of("Accept", "application/json")
            )));
            item.setDraftVersion("draft");
            item.setAuthConfigId(21L);

            CapabilityAuthConfig authConfig = new CapabilityAuthConfig();
            authConfig.setId(21L);
            authConfig.setGroupCode("payment_domain");
            authConfig.setAuthType("JWT");
            authConfig.setConfigJson(objectMapper.writeValueAsString(Map.of("token", "demo-token")));

            when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
            when(itemRepository.findByGroupCodeAndCapabilityCode("payment_domain", "service_health_check")).thenReturn(Optional.of(item));
            when(authConfigRepository.findById(21L)).thenReturn(Optional.of(authConfig));

            CapabilityService capabilityService = new CapabilityService(
                    groupRepository,
                    itemRepository,
                    snapshotRepository,
                    authConfigRepository,
                    testRecordRepository,
                    objectMapper
            );

            Map<String, Object> result = capabilityService.testCapability(1L, "service_health_check", Map.of(
                    "testType", "request"
            ));

            assertThat(result).containsEntry("success", true);
            assertThat(result.get("responsePayload")).asString().contains("\"status\":\"UP\"");
            assertThat(result.get("responsePayload")).asString().contains("\"statusCode\":200");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testCapabilityForApiReturnsFailureWhenTargetIsUnreachable() throws Exception {
        CapabilityGroupRepository groupRepository = mock(CapabilityGroupRepository.class);
        CapabilityItemRepository itemRepository = mock(CapabilityItemRepository.class);
        CapabilityGroupSnapshotRepository snapshotRepository = mock(CapabilityGroupSnapshotRepository.class);
        CapabilityAuthConfigRepository authConfigRepository = mock(CapabilityAuthConfigRepository.class);
        CapabilityTestRecordRepository testRecordRepository = mock(CapabilityTestRecordRepository.class);

        CapabilityGroup group = new CapabilityGroup();
        group.setId(1L);
        group.setGroupCode("payment_domain");
        group.setGroupName("Payment");

        CapabilityItem item = new CapabilityItem();
        item.setGroupCode("payment_domain");
        item.setCapabilityCode("service_health_check");
        item.setCapabilityName("Service Health Check");
        item.setCapabilityType(CapabilityType.API);
        item.setDefinitionJson(objectMapper.writeValueAsString(Map.of(
                "url", "http://127.0.0.1:6553/health",
                "method", "GET",
                "headers", Map.of("Accept", "application/json")
        )));
        item.setDraftVersion("draft");
        item.setAuthConfigId(21L);

        CapabilityAuthConfig authConfig = new CapabilityAuthConfig();
        authConfig.setId(21L);
        authConfig.setGroupCode("payment_domain");
        authConfig.setAuthType("JWT");
        authConfig.setConfigJson(objectMapper.writeValueAsString(Map.of("token", "demo-token")));

        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
        when(itemRepository.findByGroupCodeAndCapabilityCode("payment_domain", "service_health_check")).thenReturn(Optional.of(item));
        when(authConfigRepository.findById(21L)).thenReturn(Optional.of(authConfig));

        CapabilityService capabilityService = new CapabilityService(
                groupRepository,
                itemRepository,
                snapshotRepository,
                authConfigRepository,
                testRecordRepository,
                objectMapper
        );

        Map<String, Object> result = capabilityService.testCapability(1L, "service_health_check", Map.of(
                "testType", "request"
        ));

        assertThat(result).containsEntry("success", false);
        assertThat(result.get("errorMessage")).asString().isNotBlank();
    }

    @Test
    void testCapabilityRejectsMissingConfiguredAuth() throws Exception {
        CapabilityGroupRepository groupRepository = mock(CapabilityGroupRepository.class);
        CapabilityItemRepository itemRepository = mock(CapabilityItemRepository.class);
        CapabilityGroupSnapshotRepository snapshotRepository = mock(CapabilityGroupSnapshotRepository.class);
        CapabilityAuthConfigRepository authConfigRepository = mock(CapabilityAuthConfigRepository.class);
        CapabilityTestRecordRepository testRecordRepository = mock(CapabilityTestRecordRepository.class);

        CapabilityGroup group = new CapabilityGroup();
        group.setId(1L);
        group.setGroupCode("payment_domain");

        CapabilityItem item = new CapabilityItem();
        item.setGroupCode("payment_domain");
        item.setCapabilityCode("service_health_check");
        item.setCapabilityName("Service Health Check");
        item.setCapabilityType(CapabilityType.API);
        item.setDefinitionJson(objectMapper.writeValueAsString(Map.of(
                "url", "http://127.0.0.1:8080/health",
                "method", "GET"
        )));
        item.setDraftVersion("draft");

        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
        when(itemRepository.findByGroupCodeAndCapabilityCode("payment_domain", "service_health_check")).thenReturn(Optional.of(item));

        CapabilityService capabilityService = new CapabilityService(
                groupRepository,
                itemRepository,
                snapshotRepository,
                authConfigRepository,
                testRecordRepository,
                objectMapper
        );

        assertThatThrownBy(() -> capabilityService.testCapability(1L, "service_health_check", Map.of(
                "testType", "request"
        ))).hasMessageContaining("不能为空");
    }

    @Test
    void testCapabilityForSkillReturnsUnsupportedFailure() throws Exception {
        CapabilityGroupRepository groupRepository = mock(CapabilityGroupRepository.class);
        CapabilityItemRepository itemRepository = mock(CapabilityItemRepository.class);
        CapabilityGroupSnapshotRepository snapshotRepository = mock(CapabilityGroupSnapshotRepository.class);
        CapabilityAuthConfigRepository authConfigRepository = mock(CapabilityAuthConfigRepository.class);
        CapabilityTestRecordRepository testRecordRepository = mock(CapabilityTestRecordRepository.class);

        CapabilityGroup group = new CapabilityGroup();
        group.setId(1L);
        group.setGroupCode("payment_domain");
        group.setGroupName("Payment");

        CapabilityItem item = new CapabilityItem();
        item.setGroupCode("payment_domain");
        item.setCapabilityCode("skill_health_check");
        item.setCapabilityName("Skill Health Check");
        item.setCapabilityType(CapabilityType.SKILL);
        item.setDefinitionJson(objectMapper.writeValueAsString(Map.of(
                "skill_name", "health.check",
                "executor_type", "sync"
        )));
        item.setDraftVersion("draft");

        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
        when(itemRepository.findByGroupCodeAndCapabilityCode("payment_domain", "skill_health_check")).thenReturn(Optional.of(item));

        CapabilityService capabilityService = new CapabilityService(
                groupRepository,
                itemRepository,
                snapshotRepository,
                authConfigRepository,
                testRecordRepository,
                objectMapper
        );

        Map<String, Object> result = capabilityService.testCapability(1L, "skill_health_check", Map.of(
                "testType", "validate"
        ));

        assertThat(result).containsEntry("success", false).containsEntry("testType", "validate");
        assertThat(result.get("errorMessage")).asString().contains("仅支持 API");
    }

    @Test
    void publishCapabilityRejectsUnsupportedTypes() {
        CapabilityGroupRepository groupRepository = mock(CapabilityGroupRepository.class);
        CapabilityItemRepository itemRepository = mock(CapabilityItemRepository.class);
        CapabilityGroupSnapshotRepository snapshotRepository = mock(CapabilityGroupSnapshotRepository.class);
        CapabilityAuthConfigRepository authConfigRepository = mock(CapabilityAuthConfigRepository.class);
        CapabilityTestRecordRepository testRecordRepository = mock(CapabilityTestRecordRepository.class);

        CapabilityGroup group = new CapabilityGroup();
        group.setId(1L);
        group.setGroupCode("payment_domain");
        group.setGroupName("Payment");

        CapabilityItem item = new CapabilityItem();
        item.setGroupCode("payment_domain");
        item.setCapabilityCode("skill_health_check");
        item.setCapabilityName("Skill Health Check");
        item.setCapabilityType(CapabilityType.SKILL);
        item.setDraftVersion("draft");

        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
        when(itemRepository.findByGroupCodeAndCapabilityCode("payment_domain", "skill_health_check")).thenReturn(Optional.of(item));

        CapabilityService capabilityService = new CapabilityService(
                groupRepository,
                itemRepository,
                snapshotRepository,
                authConfigRepository,
                testRecordRepository,
                objectMapper
        );

        assertThatThrownBy(() -> capabilityService.publishCapability(1L, "skill_health_check"))
                .hasMessageContaining("不支持");
    }
}
