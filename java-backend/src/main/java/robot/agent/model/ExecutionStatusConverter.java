package robot.agent.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class ExecutionStatusConverter implements AttributeConverter<ExecutionStatus, String> {
    @Override
    public String convertToDatabaseColumn(ExecutionStatus attribute) {
        return attribute == null ? null : attribute.getValue();
    }

    @Override
    public ExecutionStatus convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        for (ExecutionStatus status : ExecutionStatus.values()) {
            if (status.getValue().equalsIgnoreCase(dbData)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown execution status: " + dbData);
    }
}
