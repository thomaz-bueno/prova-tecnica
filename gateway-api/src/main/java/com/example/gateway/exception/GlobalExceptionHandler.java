package com.example.gateway.exception;

import com.example.gateway.dto.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(OrdersApiUnavailableException.class)
    public ResponseEntity<Object> handleOrdersUnavailable(OrdersApiUnavailableException exception,
                                                         WebRequest request) {
        return handleExceptionInternal(exception,
                ApiErrorResponse.of(HttpStatus.BAD_GATEWAY, "ORDERS_API_UNAVAILABLE",
                        exception.getMessage(), path(request), Map.of()),
                new HttpHeaders(), HttpStatus.BAD_GATEWAY, request);
    }

    @ExceptionHandler(OrdersApiTimeoutException.class)
    public ResponseEntity<Object> handleOrdersTimeout(OrdersApiTimeoutException exception,
                                                     WebRequest request) {
        return handleExceptionInternal(exception,
                ApiErrorResponse.of(HttpStatus.GATEWAY_TIMEOUT, "ORDERS_API_TIMEOUT",
                        exception.getMessage(), path(request), Map.of()),
                new HttpHeaders(), HttpStatus.GATEWAY_TIMEOUT, request);
    }

    @ExceptionHandler(OrdersApiResponseException.class)
    public ResponseEntity<Object> handleOrdersResponse(OrdersApiResponseException exception,
                                                      WebRequest request) {
        return handleExceptionInternal(exception,
                ApiErrorResponse.of(HttpStatus.BAD_GATEWAY, "ORDERS_API_BAD_RESPONSE",
                        exception.getMessage(), path(request), Map.of()),
                new HttpHeaders(), HttpStatus.BAD_GATEWAY, request);
    }

    @ExceptionHandler(AuthenticationServiceException.class)
    public ResponseEntity<Object> handleAuthenticationServiceFailure(
            AuthenticationServiceException exception, WebRequest request) {
        return handleUnexpected(exception, request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Object> handleAuthentication(AuthenticationException exception,
                                                      WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return handleExceptionInternal(exception,
                ApiErrorResponse.of(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                        "Invalid username or password", path(request), Map.of()),
                headers, HttpStatus.UNAUTHORIZED, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException exception,
                                                    WebRequest request) {
        return handleExceptionInternal(exception,
                ApiErrorResponse.of(HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "Access to this resource is denied", path(request), Map.of()),
                new HttpHeaders(), HttpStatus.FORBIDDEN, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception exception, WebRequest request) {
        return handleExceptionInternal(exception, null, new HttpHeaders(),
                HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return handleExceptionInternal(exception,
                ApiErrorResponse.of(status, "VALIDATION_ERROR", "Request validation failed",
                        path(request), errors), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ApiErrorResponse response = body instanceof ApiErrorResponse error ? error
                : ApiErrorResponse.of(status, defaultCode(status), defaultMessage(status),
                        path(request), Map.of());
        if (status.is5xxServerError()) {
            if (exception instanceof OrdersApiUnavailableException || exception instanceof OrdersApiTimeoutException
                    || exception instanceof OrdersApiResponseException) {
                log.warn("event=ORDERS_API_COMMUNICATION_FAILED status={} type={}",
                        status.value(), exception.getClass().getSimpleName());
            } else {
                log.error("event=REQUEST_FAILED status={} type={}",
                        status.value(), exception.getClass().getSimpleName());
            }
        }
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.addAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        return super.handleExceptionInternal(exception, response, responseHeaders, status, request);
    }

    private String path(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest
                ? servletRequest.getRequest().getRequestURI() : "/";
    }

    private String defaultCode(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return "INTERNAL_ERROR";
        }
        return switch (status.value()) {
            case 400 -> "INVALID_REQUEST";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "HTTP_ERROR";
        };
    }

    private String defaultMessage(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return "An unexpected internal error occurred";
        }
        return switch (status.value()) {
            case 400 -> "Request contains invalid JSON or unsupported fields";
            case 404 -> "Requested resource not found";
            case 405 -> "HTTP method is not supported for this resource";
            case 415 -> "Request content type is not supported";
            default -> "Request could not be processed";
        };
    }
}
