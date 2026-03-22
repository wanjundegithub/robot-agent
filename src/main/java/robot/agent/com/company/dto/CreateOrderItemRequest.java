package robot.agent.com.company.dto;

import java.math.BigDecimal;

public record CreateOrderItemRequest(
        String productCode,
        Integer quantity,
        BigDecimal unitPrice
) {
}
