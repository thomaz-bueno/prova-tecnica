package com.example.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiErrorResponse(Instant timestamp, int status, String error, String code,
                               String message, String path, Map<String, String> errors) {

    public static ApiErrorResponse of(HttpStatusCode status, String code, String message,
                                      String path, Map<String, String> errors) {
        HttpStatus httpStatus = HttpStatus.resolve(status.value());
        return new ApiErrorResponse(Instant.now(), status.value(),
                httpStatus == null ? "HTTP Error" : httpStatus.getReasonPhrase(),
                code, message, path, Map.copyOf(errors));
    }
}
