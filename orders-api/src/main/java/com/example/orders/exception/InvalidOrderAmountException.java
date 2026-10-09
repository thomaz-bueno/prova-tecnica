package com.example.orders.exception;

public class InvalidOrderAmountException extends RuntimeException {

    public InvalidOrderAmountException() {
        super("Calculated amount exceeds the supported limit of 99999999999999999.99");
    }
}
