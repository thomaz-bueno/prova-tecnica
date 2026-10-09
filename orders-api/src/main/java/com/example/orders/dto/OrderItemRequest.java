package com.example.orders.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record OrderItemRequest(
        @NotBlank(message = "Product name must not be blank")
        @Size(max = 255, message = "Product name must not exceed 255 characters") String productName,
        @NotNull(message = "Quantity is required")
        @Positive(message = "Quantity must be greater than zero") Integer quantity,
        @NotNull(message = "Unit price is required")
        @DecimalMin(value = "0", inclusive = false, message = "Unit price must be greater than zero")
        @Digits(integer = 17, fraction = 2,
                message = "Unit price must have at most 17 integer digits and 2 decimal places") BigDecimal unitPrice
) {
}
