package com.example.gateway.controller;

import com.example.gateway.service.OrdersProxyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrdersProxyController {

    private final OrdersProxyService ordersProxyService;

    public OrdersProxyController(OrdersProxyService ordersProxyService) {
        this.ordersProxyService = ordersProxyService;
    }

    @RequestMapping(path = {"/api/orders", "/api/orders/**"})
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                        @AuthenticationPrincipal Jwt jwt,
                                        @RequestBody(required = false) byte[] body) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return ordersProxyService.forward(HttpMethod.valueOf(request.getMethod()), path,
                request.getQueryString(), jwt.getTokenValue(), request.getHeader("Content-Type"),
                request.getHeader("Accept"), body);
    }
}
