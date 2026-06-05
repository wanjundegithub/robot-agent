package robot.agent.apicenter.service;

import org.junit.jupiter.api.Test;
import robot.agent.apicenter.model.ApiAuthType;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiAuthResolverTest {

    private final ApiAuthResolver resolver = new ApiAuthResolver();

    @Test
    void bearerInjectsAuthorizationWhenHeaderIsAbsent() {
        ApiAuthConfigService.EffectiveAuth auth = new ApiAuthConfigService.EffectiveAuth(
                ApiAuthType.BEARER,
                Map.of("token", "secret-token"),
                "Bearer ••••oken"
        );

        ApiAuthResolver.AuthAppliedRequest request = resolver.apply("https://example.com/users", Map.of(), auth);

        assertThat(request.headers()).containsEntry("Authorization", "Bearer secret-token");
    }

    @Test
    void explicitAuthorizationHeaderWinsOverBearerAuth() {
        ApiAuthConfigService.EffectiveAuth auth = new ApiAuthConfigService.EffectiveAuth(
                ApiAuthType.BEARER,
                Map.of("token", "generated-token"),
                "Bearer ••••oken"
        );
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer manual-token");

        ApiAuthResolver.AuthAppliedRequest request = resolver.apply("https://example.com/users", headers, auth);

        assertThat(request.headers()).containsEntry("Authorization", "Bearer manual-token");
    }

    @Test
    void explicitAuthorizationHeaderWinsCaseInsensitive() {
        ApiAuthConfigService.EffectiveAuth auth = new ApiAuthConfigService.EffectiveAuth(
                ApiAuthType.BEARER,
                Map.of("token", "generated-token"),
                "Bearer ••••oken"
        );
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("authorization", "Bearer manual-token");

        ApiAuthResolver.AuthAppliedRequest request = resolver.apply("https://example.com/users", headers, auth);

        assertThat(request.headers()).containsEntry("authorization", "Bearer manual-token");
        assertThat(request.headers()).doesNotContainKey("Authorization");
    }

    @Test
    void apiKeyHeaderInjectsConfiguredHeaderWhenAbsent() {
        ApiAuthConfigService.EffectiveAuth auth = new ApiAuthConfigService.EffectiveAuth(
                ApiAuthType.API_KEY,
                Map.of("key", "X-API-Key", "value", "generated-key", "addTo", "HEADER"),
                "API Key header:X-API-Key"
        );

        ApiAuthResolver.AuthAppliedRequest request = resolver.apply("https://example.com/users", Map.of(), auth);

        assertThat(request.headers()).containsEntry("X-API-Key", "generated-key");
    }

    @Test
    void explicitApiKeyHeaderWinsOverGeneratedHeader() {
        ApiAuthConfigService.EffectiveAuth auth = new ApiAuthConfigService.EffectiveAuth(
                ApiAuthType.API_KEY,
                Map.of("key", "X-API-Key", "value", "generated-key", "addTo", "HEADER"),
                "API Key header:X-API-Key"
        );
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-API-Key", "manual-key");

        ApiAuthResolver.AuthAppliedRequest request = resolver.apply("https://example.com/users", headers, auth);

        assertThat(request.headers()).containsEntry("X-API-Key", "manual-key");
    }

    @Test
    void apiKeyQueryAppendsWhenQueryParamIsAbsent() {
        ApiAuthConfigService.EffectiveAuth auth = new ApiAuthConfigService.EffectiveAuth(
                ApiAuthType.API_KEY,
                Map.of("key", "api_key", "value", "generated-key", "addTo", "QUERY"),
                "API Key query:api_key"
        );

        ApiAuthResolver.AuthAppliedRequest request = resolver.apply("https://example.com/users?active=true", Map.of(), auth);

        assertThat(request.url()).isEqualTo("https://example.com/users?active=true&api_key=generated-key");
    }

    @Test
    void apiKeyQueryKeepsUrlTemplateVariablesWhenAppendingQuery() {
        ApiAuthConfigService.EffectiveAuth auth = new ApiAuthConfigService.EffectiveAuth(
                ApiAuthType.API_KEY,
                Map.of("key", "api_key", "value", "generated-key", "addTo", "QUERY"),
                "API Key query:api_key"
        );

        ApiAuthResolver.AuthAppliedRequest request = resolver.apply("https://example.com/users/{userId}", Map.of(), auth);

        assertThat(request.url()).isEqualTo("https://example.com/users/{userId}?api_key=generated-key");
    }

    @Test
    void explicitQueryParamWinsOverGeneratedApiKeyQuery() {
        ApiAuthConfigService.EffectiveAuth auth = new ApiAuthConfigService.EffectiveAuth(
                ApiAuthType.API_KEY,
                Map.of("key", "api_key", "value", "generated-key", "addTo", "QUERY"),
                "API Key query:api_key"
        );

        ApiAuthResolver.AuthAppliedRequest request = resolver.apply("https://example.com/users?api_key=manual", Map.of(), auth);

        assertThat(request.url()).isEqualTo("https://example.com/users?api_key=manual");
    }

    @Test
    void noAuthInjectsNothing() {
        ApiAuthConfigService.EffectiveAuth auth = new ApiAuthConfigService.EffectiveAuth(
                ApiAuthType.NO_AUTH,
                Map.of(),
                "No Auth"
        );

        ApiAuthResolver.AuthAppliedRequest request = resolver.apply("https://example.com/users", Map.of(), auth);

        assertThat(request.url()).isEqualTo("https://example.com/users");
        assertThat(request.headers()).isEmpty();
    }
}
