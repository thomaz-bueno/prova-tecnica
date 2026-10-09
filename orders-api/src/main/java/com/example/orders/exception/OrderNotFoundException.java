package com.example.orders.exception;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(Long id) {
        super("Order " + id + " not found");
    }
}
