package robot.agent.com.company.dto;

import robot.agent.com.company.model.OrderStatus;

public record UpdateOrderStatusRequest(OrderStatus status) {
}
