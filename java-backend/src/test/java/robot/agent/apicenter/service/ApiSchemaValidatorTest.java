package robot.agent.apicenter.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiSchemaValidatorTest {

    private final ApiSchemaValidator validator = new ApiSchemaValidator();

    @Test
    void validateSchema_acceptsDraft7Formats() {
        String schema = """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "properties": {
                    "email": { "type": "string", "format": "email" },
                    "userId": { "type": "string", "format": "uuid" },
                    "createdAt": { "type": "string", "format": "date-time" }
                  },
                  "required": ["email", "userId", "createdAt"],
                  "additionalProperties": false
                }
                """;

        ApiSchemaValidationResult result = validator.validateSchema(schema, "输入Schema");

        assertThat(result.valid()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void validatePayload_rejectsInvalidFormatValues() {
        String schema = """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "properties": {
                    "email": { "type": "string", "format": "email" }
                  },
                  "required": ["email"],
                  "additionalProperties": false
                }
                """;

        ApiSchemaValidationResult result = validator.validatePayload(schema, Map.of("email", "not-email"), "请求体");

        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).extracting(ApiSchemaValidationIssue::field).contains("请求体");
    }

    @Test
    void validateSchema_rejectsInvalidJson() {
        assertThatThrownBy(() -> validator.validateSchema("{\"$schema\":", "输入Schema"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON");
    }
}
