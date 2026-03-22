package robot.agent.com.company.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;

public record Order(
        Long id,
        String customerId,
        String shippingAddress,
        List<OrderItem> items,
        OrderStatus status,
        BigDecimal totalAmount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public Order {
        items = List.copyOf(items);
        totalAmount = normalize(totalAmount);
    }

    public Order withId(Long newId) {
        return new Order(newId, customerId, shippingAddress, items, status, totalAmount, createdAt, updatedAt);
    }

    public Order withStatus(OrderStatus newStatus, OffsetDateTime newUpdatedAt) {
        return new Order(id, customerId, shippingAddress, items, newStatus, totalAmount, createdAt, newUpdatedAt);
    }

    private static BigDecimal normalize(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
