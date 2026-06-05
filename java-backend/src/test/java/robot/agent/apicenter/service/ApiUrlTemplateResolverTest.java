package robot.agent.apicenter.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiUrlTemplateResolverTest {

    private final ApiUrlTemplateResolver resolver = new ApiUrlTemplateResolver();

    @Test
    void extractVariables_returnsOrderedDistinctVariables() {
        List<String> variables = resolver.extractVariables("https://example.com/users/{userId}/orders?status={status}&page={page}&again={userId}");

        assertThat(variables).containsExactly("userId", "status", "page");
    }

    @Test
    void resolve_replacesVariablesInUrl() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("userId", "u-1");
        values.put("status", "paid");
        values.put("page", "2");

        String resolved = resolver.resolve("https://example.com/users/{userId}/orders?status={status}&page={page}", values);

        assertThat(resolved).isEqualTo("https://example.com/users/u-1/orders?status=paid&page=2");
    }
}
