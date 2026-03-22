package robot.agent.com.company.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record OrderItem(
        String productCode,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal
) {
    public OrderItem {
        unitPrice = normalize(unitPrice);
        lineTotal = normalize(lineTotal);
    }

    private static BigDecimal normalize(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
