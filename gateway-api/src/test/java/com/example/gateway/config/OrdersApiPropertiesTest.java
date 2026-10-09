package com.example.gateway.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrdersApiPropertiesTest {

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8081", "https://orders.example/"})
    void acceptsAnHttpOriginAndPositiveTimeouts(String url) {
        var properties = new OrdersApiProperties(URI.create(url), Duration.ofSeconds(2), Duration.ofSeconds(5));
        assertThat(properties.url().toString()).isEqualTo(url);
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"/orders", "ftp://orders.example", "http://user:password@orders.example",
            "http://orders.example/api", "http://orders.example?token=secret", "http://orders.example#fragment"})
    void rejectsUrlsThatCouldLeakCredentialsOrChangeTheProxiedDestination(String url) {
        URI uri = url == null ? null : URI.create(url);
        assertThatThrownBy(() -> new OrdersApiProperties(uri, Duration.ofSeconds(2), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ORDERS_API_URL must be an HTTP(S) origin without credentials, path or query");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 2147483648L})
    void rejectsConnectionTimeoutsOutsideTheSupportedRange(long milliseconds) {
        assertThatThrownBy(() -> new OrdersApiProperties(URI.create("http://localhost:8081"),
                Duration.ofMillis(milliseconds), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingTimeouts() {
        URI uri = URI.create("http://localhost:8081");
        assertThatThrownBy(() -> new OrdersApiProperties(uri, null, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrdersApiProperties(uri, Duration.ofSeconds(2), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void rejectsNonPositiveRequestTimeouts(long milliseconds) {
        assertThatThrownBy(() -> new OrdersApiProperties(URI.create("http://localhost:8081"),
                Duration.ofSeconds(2), Duration.ofMillis(milliseconds)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
