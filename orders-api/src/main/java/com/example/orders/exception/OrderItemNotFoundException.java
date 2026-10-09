package com.example.orders.exception;

public class OrderItemNotFoundException extends RuntimeException {

    public OrderItemNotFoundException(Long orderId, Long itemId) {
        super("Order item " + itemId + " not found in order " + orderId);
    }
}
