package robot.agent.apicenter.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import robot.agent.apicenter.model.ApiGroup;
import robot.agent.apicenter.model.ApiItem;
import robot.agent.apicenter.model.ApiAuthScopeType;
import robot.agent.apicenter.repository.ApiAuthConfigRepository;
import robot.agent.apicenter.repository.ApiGroupRepository;
import robot.agent.apicenter.repository.ApiItemRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiCenterServiceSaveGateTest {

    private final ApiGroupRepository groupRepository = mock(ApiGroupRepository.class);
    private final ApiItemRepository itemRepository = mock(ApiItemRepository.class);
    private final ApiAuthConfigRepository authConfigRepository = mock(ApiAuthConfigRepository.class);
    private ApiCenterService service;

    @BeforeEach
    void setUp() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ApiAuthConfigService authConfigService = new ApiAuthConfigService(authConfigRepository, new ApiAuthCryptoService(), objectMapper);
        service = new ApiCenterService(
                groupRepository,
                itemRepository,
                new ApiSchemaValidator(),
                new ApiUrlTemplateResolver(),
                new ApiHeaderCryptoService(),
                new ApiRequestSafetyValidator(),
                authConfigService,
                new ApiAuthResolver(),
                new ApiDigestAuthService(),
                objectMapper
        );
        ApiGroup group = new ApiGroup();
        group.setId(1L);
        group.setGroupName("用户API组");
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
        when(authConfigRepository.findByScopeTypeAndScopeId(any(), any())).thenReturn(Optional.empty());
        when(authConfigRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void saveItem_acceptsSchemaValidPayloadWithoutRequestTestToken() {
        Map<String, Object> payload = validPayload();
        payload.remove("lastTestToken");
        when(itemRepository.save(any(ApiItem.class))).thenAnswer(invocation -> {
            ApiItem item = invocation.getArgument(0);
            item.setId(11L);
            return item;
        });

        service.saveItem(1L, null, payload);

        verify(itemRepository).save(any(ApiItem.class));
    }

    @Test
    void saveItem_acceptsBlankInputAndOutputSchemas() {
        Map<String, Object> payload = validPayload();
        payload.put("inputSchema", "");
        payload.put("outputSchema", "");
        ApiItem[] savedItem = new ApiItem[1];
        when(itemRepository.save(any(ApiItem.class))).thenAnswer(invocation -> {
            ApiItem item = invocation.getArgument(0);
            item.setId(12L);
            savedItem[0] = item;
            return item;
        });

        Map<String, Object> result = service.saveItem(1L, null, payload);

        verify(itemRepository).save(any(ApiItem.class));
        org.assertj.core.api.Assertions.assertThat(savedItem[0].getInputSchema()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(savedItem[0].getOutputSchema()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(result.get("inputSchema")).isEqualTo("");
        org.assertj.core.api.Assertions.assertThat(result.get("outputSchema")).isEqualTo("");
    }

    @Test
    void validateDraft_acceptsPostmanStyleHeaderArray() {
        Map<String, Object> payload = validPayload();
        payload.put("headers", List.of(
                Map.of("key", "Authorization", "value", "Bearer demo", "enabled", true),
                Map.of("key", "Content-Type", "value", "application/json", "enabled", true),
                Map.of("key", "X-Disabled", "value", "ignored", "enabled", false)
        ));

        Map<String, Object> validation = service.validateDraft(1L, payload);

        org.assertj.core.api.Assertions.assertThat(validation.get("valid")).isEqualTo(true);
    }

    @Test
    void validateDraft_acceptsAuthorizationHeaderWhenAuthModeIsNone() {
        Map<String, Object> payload = validPayload();
        payload.put("authMode", "NONE");
        payload.put("headers", List.of(Map.of("key", "Authorization", "value", "Bearer manual", "enabled", true)));

        Map<String, Object> validation = service.validateDraft(1L, payload);

        org.assertj.core.api.Assertions.assertThat(validation.get("valid")).isEqualTo(true);
    }

    @Test
    void validateDraft_rejectsCustomBearerWithoutToken() {
        Map<String, Object> payload = validPayload();
        payload.put("authMode", "CUSTOM");
        payload.put("authConfig", Map.of("authType", "BEARER"));

        Map<String, Object> validation = service.validateDraft(1L, payload);

        org.assertj.core.api.Assertions.assertThat(validation.get("valid")).isEqualTo(false);
        org.assertj.core.api.Assertions.assertThat(String.valueOf(validation.get("issues"))).contains("token 不能为空");
    }

    @Test
    void saveItem_persistsCustomAuthModeAndKeepsManualAuthorizationHeader() {
        Map<String, Object> payload = validPayload();
        payload.put("authMode", "CUSTOM");
        payload.put("authConfig", Map.of("authType", "BEARER", "token", "generated"));
        payload.put("headers", List.of(Map.of("key", "Authorization", "value", "Bearer manual", "enabled", true)));
        when(itemRepository.save(any(ApiItem.class))).thenAnswer(invocation -> {
            ApiItem item = invocation.getArgument(0);
            item.setId(13L);
            return item;
        });

        Map<String, Object> result = service.saveItem(1L, null, payload);

        org.assertj.core.api.Assertions.assertThat(result.get("authMode")).isEqualTo("CUSTOM");
        org.assertj.core.api.Assertions.assertThat(result.get("authPreview")).asString().contains("Bearer");
        verify(itemRepository).save(any(ApiItem.class));
    }

    @Test
    void saveItem_removesStaleCustomAuthConfigWhenSwitchingToNone() {
        ApiItem existing = new ApiItem();
        existing.setId(23L);
        existing.setGroupId(1L);
        existing.setApiName("旧API");
        existing.setRequestUrl("https://example.com/users");
        existing.setRequestMethod("GET");
        existing.setAuthMode("CUSTOM");
        when(itemRepository.findById(23L)).thenReturn(Optional.of(existing));
        when(itemRepository.save(any(ApiItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Map<String, Object> payload = validPayload();
        payload.put("authMode", "NONE");

        service.saveItem(1L, 23L, payload);

        verify(authConfigRepository).deleteByScopeTypeAndScopeId(ApiAuthScopeType.ITEM, 23L);
    }

    @Test
    void saveItemAuthConfig_removesStaleCustomConfigWhenSwitchingToInherit() {
        ApiItem existing = new ApiItem();
        existing.setId(24L);
        existing.setGroupId(1L);
        existing.setAuthMode("CUSTOM");
        when(itemRepository.findById(24L)).thenReturn(Optional.of(existing));
        when(itemRepository.save(any(ApiItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.saveItemAuthConfig(1L, 24L, Map.of("authMode", "INHERIT"));

        verify(authConfigRepository).deleteByScopeTypeAndScopeId(ApiAuthScopeType.ITEM, 24L);
    }

    @Test
    void deleteGroup_removesGroupAuthConfigAndItemAuthConfigs() {
        ApiItem item = new ApiItem();
        item.setId(21L);
        item.setGroupId(1L);
        when(itemRepository.findByGroupIdOrderByUpdatedAtDesc(1L)).thenReturn(List.of(item));

        service.deleteGroup(1L);

        verify(authConfigRepository).deleteByScopeTypeAndScopeId(ApiAuthScopeType.ITEM, 21L);
        verify(authConfigRepository).deleteByScopeTypeAndScopeId(ApiAuthScopeType.GROUP, 1L);
        verify(itemRepository).deleteByGroupId(1L);
    }

    @Test
    void deleteItem_removesItemAuthConfig() {
        ApiItem item = new ApiItem();
        item.setId(22L);
        item.setGroupId(1L);
        when(itemRepository.findById(22L)).thenReturn(Optional.of(item));

        service.deleteItem(1L, 22L);

        verify(authConfigRepository).deleteByScopeTypeAndScopeId(ApiAuthScopeType.ITEM, 22L);
        verify(itemRepository).delete(item);
    }

    @Test
    void validateDraft_rejectsNonObjectHeaderArrayItem() {
        Map<String, Object> payload = validPayload();
        payload.put("headers", List.of("Authorization"));

        Map<String, Object> validation = service.validateDraft(1L, payload);

        org.assertj.core.api.Assertions.assertThat(validation.get("valid")).isEqualTo(false);
        org.assertj.core.api.Assertions.assertThat(String.valueOf(validation.get("issues"))).contains("Header 数组项必须是对象");
    }

    @Test
    void validateDraft_rejectsHeaderObjectFormat() {
        Map<String, Object> payload = validPayload();
        payload.put("headers", Map.of("Authorization", "Bearer demo"));

        Map<String, Object> validation = service.validateDraft(1L, payload);

        org.assertj.core.api.Assertions.assertThat(validation.get("valid")).isEqualTo(false);
        org.assertj.core.api.Assertions.assertThat(String.valueOf(validation.get("issues"))).contains("Header 必须是 Postman 数组格式");
    }

    private Map<String, Object> validPayload() {
        return new java.util.LinkedHashMap<>(Map.of(
                "apiName", "查询用户",
                "requestUrl", "https://example.com/users?userId={userId}",
                "requestMethod", "GET",
                "headers", List.of(),
                "inputSchema", "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\",\"additionalProperties\":false}",
                "outputSchema", "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\",\"additionalProperties\":true}",
                "urlVariables", Map.of("userId", "u-1"),
                "body", Map.of()
        ));
    }

}
