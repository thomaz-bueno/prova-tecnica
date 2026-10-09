package com.example.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "orders.api")
public record OrdersApiProperties(URI url, Duration connectTimeout, Duration timeout) {

    public OrdersApiProperties {
        if (url == null || url.getHost() == null
                || !("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()))
                || url.getRawUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null
                || !(url.getRawPath().isEmpty() || "/".equals(url.getRawPath()))) {
            throw new IllegalArgumentException("ORDERS_API_URL must be an HTTP(S) origin without credentials, path or query");
        }
        if (connectTimeout == null || connectTimeout.toMillis() < 1
                || connectTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Orders connection timeout must be between 1 ms and 2147483647 ms");
        }
        if (timeout == null || timeout.toMillis() < 1) {
            throw new IllegalArgumentException("Orders request timeout must be at least 1 ms");
        }
    }
}
