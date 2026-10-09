package com.example.orders.security;

import com.example.orders.dto.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Component
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler, RequestRejectedHandler {

    private final ObjectMapper objectMapper;

    public SecurityErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        writeError(request, response, HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED", "Authentication is required or invalid");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        writeError(request, response, HttpStatus.FORBIDDEN,
                "FORBIDDEN", "Access to this resource is denied");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       RequestRejectedException exception) throws IOException {
        // Write directly: sendError would dispatch to /error and could mask this 400.
        writeError(request, response, HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST", "Request URL or headers are invalid");
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response,
                            HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiErrorResponse error = ApiErrorResponse.of(status, code, message,
                request.getRequestURI(), Map.of());
        objectMapper.writeValue(response.getOutputStream(), error);
    }
}
