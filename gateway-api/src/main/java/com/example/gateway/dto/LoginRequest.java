package com.example.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "Username must not be blank")
        @Size(max = 255, message = "Username must not exceed 255 characters") String username,
        @NotBlank(message = "Password must not be blank") String password
) {
}
