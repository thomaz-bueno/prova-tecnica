package com.example.gateway.service;

import com.example.gateway.dto.LoginRequest;
import com.example.gateway.dto.LoginResponse;
import com.example.gateway.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(AuthenticationManager authenticationManager, JwtService jwtService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    public LoginResponse login(LoginRequest request) {
        try {
            // BCrypt accepts at most 72 bytes; do not truncate or expose an internal error.
            if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
                throw new BadCredentialsException("Invalid credentials");
            }
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            request.username(), request.password()));
            LoginResponse response = new LoginResponse(jwtService.generateToken(authentication),
                    "Bearer", JwtService.TOKEN_VALIDITY_SECONDS);
            log.info("event=AUTHENTICATION_SUCCEEDED");
            return response;
        } catch (AuthenticationException exception) {
            // Never log username, password, token, exception messages or request bodies.
            log.warn("event=AUTHENTICATION_FAILED type={}", exception.getClass().getSimpleName());
            throw exception;
        }
    }
}
