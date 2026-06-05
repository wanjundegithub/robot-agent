package robot.agent.apicenter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ApiSchemaValidator {

    private static final String DRAFT_07_SCHEMA = "http://json-schema.org/draft-07/schema#";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SchemaRegistry schemaRegistry = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_7,
            builder -> builder.schemaRegistryConfig(
                    SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()
            )
    );
    private final Schema metaSchema = schemaRegistry.getSchema(SchemaLocation.of(DRAFT_07_SCHEMA));

    public ApiSchemaValidationResult validateSchema(String schemaText, String fieldName) {
        JsonNode schemaNode = readJson(schemaText, fieldName);
        List<ApiSchemaValidationIssue> issues = new ArrayList<>();
        JsonNode schemaDeclaration = schemaNode.get("$schema");
        if (schemaDeclaration == null || !DRAFT_07_SCHEMA.equals(schemaDeclaration.asText())) {
            issues.add(new ApiSchemaValidationIssue(fieldName + ".$schema", "必须声明 Draft-07: " + DRAFT_07_SCHEMA));
        }
        metaSchema.validate(schemaNode).forEach(error -> issues.add(new ApiSchemaValidationIssue(fieldName, error.getMessage())));
        if (issues.isEmpty()) {
            schemaRegistry.getSchema(schemaNode);
        }
        return new ApiSchemaValidationResult(issues.isEmpty(), issues.isEmpty() ? "校验通过" : "校验失败", issues);
    }

    public ApiSchemaValidationResult validatePayload(String schemaText, Object payload, String fieldName) {
        ApiSchemaValidationResult schemaResult = validateSchema(schemaText, fieldName + "Schema");
        if (!schemaResult.valid()) {
            return schemaResult;
        }
        try {
            JsonNode payloadNode = objectMapper.valueToTree(payload == null ? java.util.Map.of() : payload);
            Schema schema = schemaRegistry.getSchema(schemaText, InputFormat.JSON);
            List<ApiSchemaValidationIssue> issues = schema.validate(payloadNode).stream()
                    .map(error -> new ApiSchemaValidationIssue(fieldName, error.getMessage()))
                    .toList();
            return new ApiSchemaValidationResult(issues.isEmpty(), issues.isEmpty() ? "校验通过" : "校验失败", issues);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException(fieldName + "校验失败: " + exception.getMessage(), exception);
        }
    }

    private JsonNode readJson(String schemaText, String fieldName) {
        if (schemaText == null || schemaText.isBlank()) {
            throw new IllegalArgumentException(fieldName + " JSON 不能为空");
        }
        try {
            return objectMapper.readTree(schemaText);
        } catch (Exception exception) {
            throw new IllegalArgumentException(fieldName + " JSON 格式不正确", exception);
        }
    }
}
