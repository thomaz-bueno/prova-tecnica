package com.example.orders.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateOrderRequest(
        @NotBlank(message = "Customer name must not be blank")
        @Size(max = 255, message = "Customer name must not exceed 255 characters") String customerName,
        @NotBlank(message = "Customer email must not be blank")
        @Email(message = "Customer email must be a valid email address")
        @Size(max = 255, message = "Customer email must not exceed 255 characters") String customerEmail
) {
}
