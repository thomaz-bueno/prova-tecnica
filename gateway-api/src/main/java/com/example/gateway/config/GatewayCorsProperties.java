package com.example.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.List;

@ConfigurationProperties(prefix = "security.cors")
public record GatewayCorsProperties(List<String> allowedOrigins) {

    public GatewayCorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        for (String origin : allowedOrigins) {
            URI uri;
            try {
                uri = URI.create(origin);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("CORS origins must be explicit HTTP(S) origins");
            }
            if (origin.contains("*") || uri.getHost() == null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getRawUserInfo() != null || !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() > 65535) {
                throw new IllegalArgumentException("CORS origins must be explicit HTTP(S) origins");
            }
        }
    }
}
