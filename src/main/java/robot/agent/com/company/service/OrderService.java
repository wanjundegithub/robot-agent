package robot.agent.com.company.service;

import org.springframework.stereotype.Service;
import robot.agent.com.company.dto.CreateOrderItemRequest;
import robot.agent.com.company.dto.CreateOrderRequest;
import robot.agent.com.company.dto.OrderItemResponse;
import robot.agent.com.company.dto.OrderResponse;
import robot.agent.com.company.dto.UpdateOrderStatusRequest;
import robot.agent.com.company.exception.OrderNotFoundException;
import robot.agent.com.company.model.Order;
import robot.agent.com.company.model.OrderItem;
import robot.agent.com.company.model.OrderStatus;
import robot.agent.com.company.repository.OrderRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public OrderResponse createOrder(CreateOrderRequest request) {
        validateCreateRequest(request);

        List<OrderItem> items = request.items().stream()
                .map(this::toOrderItem)
                .toList();

        BigDecimal totalAmount = items.stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        OffsetDateTime now = OffsetDateTime.now();
        Order order = new Order(
                null,
                request.customerId().trim(),
                request.shippingAddress().trim(),
                items,
                OrderStatus.CREATED,
                normalize(totalAmount),
                now,
                now
        );

        return toResponse(orderRepository.save(order));
    }

    public List<OrderResponse> getOrders() {
        return orderRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    public OrderResponse getOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .map(this::toResponse)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    public OrderResponse updateStatus(Long orderId, UpdateOrderStatusRequest request) {
        if (request == null || request.status() == null) {
            throw new IllegalArgumentException("Order status is required");
        }

        Order currentOrder = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        Order updatedOrder = currentOrder.withStatus(request.status(), OffsetDateTime.now());
        return toResponse(orderRepository.save(updatedOrder));
    }

    public void deleteOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        orderRepository.deleteById(order.id());
    }

    private OrderItem toOrderItem(CreateOrderItemRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Order item is required");
        }
        if (isBlank(request.productCode())) {
            throw new IllegalArgumentException("Order item productCode is required");
        }
        if (request.quantity() == null || request.quantity() <= 0) {
            throw new IllegalArgumentException("Order item quantity must be greater than 0");
        }
        if (request.unitPrice() == null || request.unitPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Order item unitPrice must be greater than 0");
        }

        BigDecimal unitPrice = normalize(request.unitPrice());
        BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(request.quantity()));
        return new OrderItem(request.productCode().trim(), request.quantity(), unitPrice, normalize(lineTotal));
    }

    private void validateCreateRequest(CreateOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (isBlank(request.customerId())) {
            throw new IllegalArgumentException("customerId is required");
        }
        if (isBlank(request.shippingAddress())) {
            throw new IllegalArgumentException("shippingAddress is required");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("At least one order item is required");
        }
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.items().stream()
                .map(item -> new OrderItemResponse(
                        item.productCode(),
                        item.quantity(),
                        item.unitPrice(),
                        item.lineTotal()
                ))
                .toList();

        return new OrderResponse(
                order.id(),
                order.customerId(),
                order.shippingAddress(),
                order.status(),
                order.totalAmount(),
                order.createdAt(),
                order.updatedAt(),
                items
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private BigDecimal normalize(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
