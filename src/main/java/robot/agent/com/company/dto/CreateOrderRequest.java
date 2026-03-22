package robot.agent.com.company.dto;

import java.util.List;

public record CreateOrderRequest(
        String customerId,
        String shippingAddress,
        List<CreateOrderItemRequest> items
) {
}
