package com.example.orders.mapper;

import com.example.orders.dto.OrderItemResponse;
import com.example.orders.dto.OrderResponse;
import com.example.orders.dto.OrderSummaryResponse;
import com.example.orders.entity.Order;
import com.example.orders.entity.OrderItem;
import org.springframework.stereotype.Component;

@Component
public class OrderMapper {

    public OrderResponse toResponse(Order order) {
        return new OrderResponse(order.getId(), order.getCustomerName(),
                order.getCustomerEmail(), order.getOrderDate(), order.getStatus(),
                order.getItems().stream().map(this::toItemResponse).toList(),
                order.getTotalAmount());
    }

    public OrderSummaryResponse toSummaryResponse(Order order) {
        return new OrderSummaryResponse(order.getId(), order.getCustomerName(),
                order.getCustomerEmail(), order.getOrderDate(),
                order.getStatus(), order.getTotalAmount());
    }

    public OrderItemResponse toItemResponse(OrderItem item) {
        return new OrderItemResponse(item.getId(), item.getProductName(),
                item.getQuantity(), item.getUnitPrice(), item.getSubtotal());
    }
}
