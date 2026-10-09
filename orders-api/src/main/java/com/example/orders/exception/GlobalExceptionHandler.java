package com.example.orders.exception;

import com.example.orders.dto.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Object> handleOrderNotFound(
            OrderNotFoundException exception, WebRequest request) {
        return errorResponse(exception, new HttpHeaders(), HttpStatus.NOT_FOUND, request,
                "ORDER_NOT_FOUND", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(OrderItemNotFoundException.class)
    public ResponseEntity<Object> handleItemNotFound(
            OrderItemNotFoundException exception, WebRequest request) {
        return errorResponse(exception, new HttpHeaders(), HttpStatus.NOT_FOUND, request,
                "ORDER_ITEM_NOT_FOUND", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(InvalidOrderAmountException.class)
    public ResponseEntity<Object> handleInvalidAmount(
            InvalidOrderAmountException exception, WebRequest request) {
        return errorResponse(exception, new HttpHeaders(), HttpStatus.BAD_REQUEST, request,
                "INVALID_ORDER_AMOUNT", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Object> handleAuthentication(
            AuthenticationException exception, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return handleExceptionInternal(exception, null, headers,
                HttpStatus.UNAUTHORIZED, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(
            AccessDeniedException exception, WebRequest request) {
        return handleExceptionInternal(exception, null, new HttpHeaders(),
                HttpStatus.FORBIDDEN, request);
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
        return errorResponse(exception, headers, status, request,
                "VALIDATION_ERROR", "Request validation failed", errors);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (exception.isForReturnValue()) {
            return handleExceptionInternal(exception, null, headers, status, request);
        }
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getParameterValidationResults().forEach(result -> {
            if (result instanceof ParameterErrors parameterErrors) {
                parameterErrors.getFieldErrors().forEach(error ->
                        errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
            } else {
                String parameter = result.getMethodParameter().getParameterName();
                if (parameter == null) {
                    parameter = "arg" + result.getMethodParameter().getParameterIndex();
                }
                String field = parameter;
                result.getResolvableErrors().forEach(error ->
                        errors.putIfAbsent(field, error.getDefaultMessage()));
            }
        });
        return errorResponse(exception, headers, status, request,
                "VALIDATION_ERROR", "Request validation failed", errors);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ApiErrorResponse error = body instanceof ApiErrorResponse response ? response
                : ApiErrorResponse.of(status, defaultCode(status),
                        defaultMessage(status), path(request), Map.of());
        if (status.is5xxServerError()) {
            // Avoid logging exception messages, request bodies, credentials or tokens.
            log.error("event=REQUEST_FAILED status={} type={}",
                    status.value(), exception.getClass().getSimpleName());
        }
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.addAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        return super.handleExceptionInternal(exception, error, responseHeaders, status, request);
    }

    private ResponseEntity<Object> errorResponse(
            Exception exception, HttpHeaders headers, HttpStatusCode status,
            WebRequest request, String code, String message, Map<String, String> errors) {
        return handleExceptionInternal(exception,
                ApiErrorResponse.of(status, code, message, path(request), errors),
                headers, status, request);
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
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "HTTP_ERROR";
        };
    }

    private String defaultMessage(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return "An unexpected internal error occurred";
        }
        return switch (status.value()) {
            case 400 -> "Request contains invalid JSON, unsupported fields, or invalid parameter values";
            case 401 -> "Authentication is required or invalid";
            case 403 -> "Access to this resource is denied";
            case 404 -> "Requested resource not found";
            case 405 -> "HTTP method is not supported for this resource";
            case 406 -> "Requested response media type is not supported";
            case 415 -> "Request content type is not supported";
            default -> "Request could not be processed";
        };
    }
}
