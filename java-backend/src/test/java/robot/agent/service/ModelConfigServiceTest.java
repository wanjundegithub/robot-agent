package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import robot.agent.dto.request.UpsertModelProfileRequest;
import robot.agent.model.LlmModelProfile;
import robot.agent.model.LlmProviderConfig;
import robot.agent.model.Role;
import robot.agent.model.UserRole;
import robot.agent.repository.AuditLogRepository;
import robot.agent.repository.LlmModelProfileRepository;
import robot.agent.repository.LlmProviderConfigRepository;
import robot.agent.repository.UserRoleRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelConfigServiceTest {

    @Mock
    private LlmProviderConfigRepository providerRepository;

    @Mock
    private LlmModelProfileRepository profileRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Test
    void buildRuntimeBundleCollectsProfilesFromWorkflowDefinition() {
        LlmProviderConfig provider = new LlmProviderConfig();
        provider.setProviderCode("openai-compatible-prod");
        provider.setProviderType("openai_compatible");
        provider.setBaseUrl("https://llm.example.com/v1");
        provider.setDefaultModelCode("qwen-plus");

        LlmModelProfile profile = new LlmModelProfile();
        profile.setProfileCode("knowledge-answer-v1");
        profile.setProviderCode("openai-compatible-prod");
        profile.setModelCode("qwen-plus");
        profile.setPurpose("knowledge_answer");
        profile.setTemperature(BigDecimal.valueOf(0.2));
        profile.setTopP(BigDecimal.valueOf(0.9));

        when(profileRepository.findByProfileCodeIn(anyCollection()))
                .thenReturn(List.of(profile));
        when(providerRepository.findByProviderCodeIn(anyCollection()))
                .thenReturn(List.of(provider));

        ObjectMapper objectMapper = new ObjectMapper();
        ModelConfigService service = new ModelConfigService(
                providerRepository,
                profileRepository,
                objectMapper,
                new AccessControlService(userRoleRepository),
                new AuditService(auditLogRepository, objectMapper)
        );
        ModelConfigService.RuntimeModelBundle bundle = service.buildRuntimeBundle(
                List.of(Map.of(
                        "config", Map.of("intent_profile_ref", "intent-router-v1"),
                        "nodes", Map.of(
                                "answer", Map.of("config", Map.of("model_profile_ref", "knowledge-answer-v1"))
                        )
                )),
                "intent-router-v1"
        );

        assertThat(bundle.providerConfigs()).hasSize(1);
        assertThat(bundle.modelProfiles()).hasSize(1);
        assertThat(bundle.modelProfiles().get(0).get("profile_code")).isEqualTo("knowledge-answer-v1");
    }

    @Test
    void buildRuntimeBundleIncludesFallbackProfile() {
        LlmProviderConfig provider = new LlmProviderConfig();
        provider.setProviderCode("custom-provider");
        provider.setProviderType("custom");
        provider.setBaseUrl("https://proxy.example.com");
        provider.setDefaultModelCode("model-a");

        LlmModelProfile primary = new LlmModelProfile();
        primary.setProfileCode("primary-profile");
        primary.setProviderCode("custom-provider");
        primary.setModelCode("model-a");
        primary.setPurpose("chat");
        primary.setFallbackProfileCode("fallback-profile");

        LlmModelProfile fallback = new LlmModelProfile();
        fallback.setProfileCode("fallback-profile");
        fallback.setProviderCode("custom-provider");
        fallback.setModelCode("model-b");
        fallback.setPurpose("chat");

        when(profileRepository.findByProfileCodeIn(anyCollection())).thenReturn(List.of(primary));
        when(profileRepository.findByProfileCode("fallback-profile")).thenReturn(java.util.Optional.of(fallback));
        when(providerRepository.findByProviderCodeIn(anyCollection())).thenReturn(List.of(provider));

        ObjectMapper objectMapper = new ObjectMapper();
        ModelConfigService service = new ModelConfigService(
                providerRepository,
                profileRepository,
                objectMapper,
                new AccessControlService(userRoleRepository),
                new AuditService(auditLogRepository, objectMapper)
        );

        ModelConfigService.RuntimeModelBundle bundle = service.buildRuntimeBundle(
                List.of(Map.of("config", Map.of("intent_profile_ref", "primary-profile"))),
                null
        );

        assertThat(bundle.modelProfiles()).hasSize(2);
        assertThat(bundle.modelProfiles()).extracting(item -> item.get("profile_code"))
                .contains("primary-profile", "fallback-profile");
    }

    @Test
    void saveModelProfileUsesBackendPresetInsteadOfFrontendNumbers() {
        LlmProviderConfig provider = new LlmProviderConfig();
        provider.setProviderCode("doubao-provider");
        provider.setProviderType("doubao");
        provider.setBaseUrl("https://ark.example.com/api/v3");
        provider.setDefaultModelCode("doubao-seed-2-0-pro-260215");

        when(userRoleRepository.findByIdUserIdAndIdWorkspaceId("demo-admin", 1L)).thenReturn(List.of(adminRole()));
        when(profileRepository.findByProfileCode("intent-router-v1")).thenReturn(Optional.empty());
        when(providerRepository.findByProviderCode("doubao-provider")).thenReturn(Optional.of(provider));
        when(profileRepository.save(any(LlmModelProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ObjectMapper objectMapper = new ObjectMapper();
        ModelConfigService service = new ModelConfigService(
                providerRepository,
                profileRepository,
                objectMapper,
                new AccessControlService(userRoleRepository),
                new AuditService(auditLogRepository, objectMapper)
        );

        UpsertModelProfileRequest request = new UpsertModelProfileRequest();
        request.setProfileCode("intent-router-v1");
        request.setProviderCode("doubao-provider");
        request.setPurpose("intent_routing");
        request.setTemperature(BigDecimal.valueOf(0.99d));
        request.setTopP(BigDecimal.valueOf(0.01d));
        request.setMaxTokens(1);
        request.setTimeoutSec(999);
        request.setEnabled(true);

        Map<String, Object> saved = service.saveModelProfile("demo-admin", request);

        assertThat(saved.get("temperature")).isEqualTo(BigDecimal.valueOf(0.10d));
        assertThat(saved.get("top_p")).isEqualTo(BigDecimal.valueOf(0.80d));
        assertThat(saved.get("max_tokens")).isEqualTo(512);
        assertThat(saved.get("timeout_sec")).isEqualTo(15);
        assertThat(saved.get("model_code")).isEqualTo("doubao-seed-2-0-pro-260215");
    }

    @Test
    void buildProviderRequestUsesConfiguredDoubaoGenerationSettings() {
        ObjectMapper objectMapper = new ObjectMapper();
        ModelConfigService service = new ModelConfigService(
                providerRepository,
                profileRepository,
                objectMapper,
                new AccessControlService(userRoleRepository),
                new AuditService(auditLogRepository, objectMapper)
        );
        LlmProviderConfig provider = new LlmProviderConfig();
        provider.setProviderType("doubao");
        provider.setBaseUrl("https://ark.example.com/api/v3");
        provider.setDefaultModelCode("doubao-seed-2-0-pro-260215");

        Object request = ReflectionTestUtils.invokeMethod(
                service,
                "buildProviderRequest",
                provider,
                "doubao",
                "doubao-seed-2-0-pro-260215",
                "你是一个简洁可靠的机器人助手。",
                "请用一句话回复：模型测试成功。",
                BigDecimal.valueOf(0.10d),
                BigDecimal.valueOf(0.80d),
                512,
                null
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ReflectionTestUtils.invokeMethod(request, "body");

        assertThat(body.get("temperature")).isEqualTo(0.1d);
        assertThat(body.get("top_p")).isEqualTo(0.8d);
        assertThat(body.get("max_output_tokens")).isEqualTo(512);
    }

    @Test
    void extractProviderTextSupportsDoubaoOutputTextBlocks() {
        ObjectMapper objectMapper = new ObjectMapper();
        ModelConfigService service = new ModelConfigService(
                providerRepository,
                profileRepository,
                objectMapper,
                new AccessControlService(userRoleRepository),
                new AuditService(auditLogRepository, objectMapper)
        );

        String text = ReflectionTestUtils.invokeMethod(
                service,
                "extractProviderText",
                "doubao",
                Map.of(
                        "status", "completed",
                        "output", List.of(
                                Map.of(
                                        "type", "message",
                                        "content", List.of(
                                                Map.of("type", "output_text", "text", "模型测试成功")
                                        )
                                )
                        )
                )
        );

        assertThat(text).isEqualTo("模型测试成功");
    }

    private UserRole adminRole() {
        Role role = new Role();
        role.setCode("workflow_admin");
        UserRole userRole = new UserRole();
        userRole.setRole(role);
        return userRole;
    }
}
