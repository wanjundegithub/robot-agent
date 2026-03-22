package robot.agent.com.company.dto;

import java.math.BigDecimal;

public record OrderItemResponse(
        String productCode,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal
) {
}
