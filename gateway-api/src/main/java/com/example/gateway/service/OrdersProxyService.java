package com.example.gateway.service;

import com.example.gateway.config.OrdersApiProperties;
import com.example.gateway.exception.OrdersApiResponseException;
import com.example.gateway.exception.OrdersApiTimeoutException;
import com.example.gateway.exception.OrdersApiUnavailableException;
import io.netty.channel.ConnectTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeoutException;

@Service
public class OrdersProxyService {

    private static final Logger log = LoggerFactory.getLogger(OrdersProxyService.class);

    private static final List<String> RESPONSE_HEADERS = List.of(
            HttpHeaders.CONTENT_TYPE, HttpHeaders.LOCATION, HttpHeaders.ALLOW,
            HttpHeaders.WWW_AUTHENTICATE, HttpHeaders.CACHE_CONTROL, HttpHeaders.ETAG,
            HttpHeaders.LAST_MODIFIED, HttpHeaders.RETRY_AFTER, HttpHeaders.CONTENT_LANGUAGE,
            HttpHeaders.VARY, HttpHeaders.EXPIRES);

    private final WebClient ordersWebClient;
    private final OrdersApiProperties properties;

    public OrdersProxyService(@Qualifier("ordersWebClient") WebClient ordersWebClient,
                              OrdersApiProperties properties) {
        this.ordersWebClient = ordersWebClient;
        this.properties = properties;
    }

    public ResponseEntity<byte[]> forward(HttpMethod method, String path, String query,
                                           String token, String contentType, String accept, byte[] body) {
        String origin = properties.url().toASCIIString();
        if (origin.endsWith("/")) {
            origin = origin.substring(0, origin.length() - 1);
        }
        // URI overload preserves encoded query values and repeated parameters without re-encoding.
        URI destination = URI.create(origin + path + (query == null ? "" : "?" + query));
        WebClient.RequestBodySpec outgoing = ordersWebClient.method(method).uri(destination)
                .headers(headers -> {
                    headers.setBearerAuth(token);
                    if (contentType != null) {
                        headers.set(HttpHeaders.CONTENT_TYPE, contentType);
                    }
                    if (accept != null) {
                        headers.set(HttpHeaders.ACCEPT, accept);
                    }
                });
        WebClient.RequestHeadersSpec<?> request = body == null ? outgoing : outgoing.bodyValue(body);
        ResponseEntity<byte[]> response = request
                // Unlike retrieve's default status handling, preserve downstream 4xx/5xx responses.
                .exchangeToMono(upstream -> upstream.toEntity(byte[].class))
                .timeout(properties.timeout())
                .onErrorMap(this::mapFailure)
                .block();
        if (response == null) {
            throw new OrdersApiResponseException(null);
        }
        if (response.getStatusCode().is5xxServerError()) {
            log.warn("event=ORDERS_API_SERVER_ERROR status={}", response.getStatusCode().value());
        }
        HttpHeaders headers = new HttpHeaders();
        RESPONSE_HEADERS.forEach(name -> {
            List<String> values = response.getHeaders().get(name);
            if (values != null) {
                headers.put(name, List.copyOf(values));
            }
        });
        rewriteLocation(headers);
        return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
    }

    private Throwable mapFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof ConnectTimeoutException
                    || cause instanceof io.netty.handler.timeout.TimeoutException
                    || cause instanceof SocketTimeoutException) {
                return new OrdersApiTimeoutException(failure);
            }
            if (cause instanceof DataBufferLimitException) {
                return new OrdersApiResponseException(failure);
            }
        }
        if (failure instanceof WebClientRequestException) {
            return new OrdersApiUnavailableException(failure);
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return new OrdersApiUnavailableException(failure);
            }
        }
        return failure;
    }

    private void rewriteLocation(HttpHeaders headers) {
        URI location = headers.getLocation();
        if (location == null || location.getRawPath() == null) {
            return;
        }
        boolean fromOrders = !location.isAbsolute()
                || (location.getScheme().equalsIgnoreCase(properties.url().getScheme())
                && location.getRawAuthority().equalsIgnoreCase(properties.url().getRawAuthority()));
        String path = location.getRawPath();
        if (fromOrders && (path.equals("/api/orders") || path.startsWith("/api/orders/"))) {
            headers.setLocation(URI.create(path + (location.getRawQuery() == null ? "" : "?" + location.getRawQuery())
                    + (location.getRawFragment() == null ? "" : "#" + location.getRawFragment())));
        }
    }
}
