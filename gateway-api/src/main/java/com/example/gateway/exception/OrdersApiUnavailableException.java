package com.example.gateway.exception;

public class OrdersApiUnavailableException extends RuntimeException {

    public OrdersApiUnavailableException(Throwable cause) {
        super("Orders API is unavailable", cause);
    }
}
