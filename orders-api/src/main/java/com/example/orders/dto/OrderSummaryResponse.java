package com.example.orders.dto;

import com.example.orders.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderSummaryResponse(
        Long id,
        String customerName,
        String customerEmail,
        LocalDateTime orderDate,
        OrderStatus status,
        BigDecimal totalAmount
) {
}
