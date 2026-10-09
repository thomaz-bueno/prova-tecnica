package com.example.orders.dto;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long id,
        String productName,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal subtotal
) {
}
