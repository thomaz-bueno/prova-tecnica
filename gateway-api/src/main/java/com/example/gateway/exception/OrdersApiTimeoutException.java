package com.example.gateway.exception;

public class OrdersApiTimeoutException extends RuntimeException {

    public OrdersApiTimeoutException(Throwable cause) {
        super("Orders API request timed out", cause);
    }
}
