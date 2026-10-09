package com.example.gateway.dto;

public record LoginResponse(String token, String tokenType, long expiresIn) {
}
