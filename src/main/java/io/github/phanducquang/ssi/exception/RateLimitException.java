package io.github.phanducquang.ssi.exception;

import com.fasterxml.jackson.databind.JsonNode;

public class RateLimitException extends SsiApiException {
    private final Double retryAfterSeconds;
    public RateLimitException(String message, int statusCode, JsonNode responseBody, Double retryAfterSeconds) { super(message, statusCode, responseBody); this.retryAfterSeconds = retryAfterSeconds; }
    public Double retryAfterSeconds() { return retryAfterSeconds; }
}
