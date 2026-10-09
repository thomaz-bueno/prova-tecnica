package com.example.gateway.exception;

public class OrdersApiResponseException extends RuntimeException {

    public OrdersApiResponseException(Throwable cause) {
        super("Orders API returned an unreadable response", cause);
    }
}
