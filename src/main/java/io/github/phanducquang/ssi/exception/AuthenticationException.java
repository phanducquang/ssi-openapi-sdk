package io.github.phanducquang.ssi.exception;

import com.fasterxml.jackson.databind.JsonNode;

public class AuthenticationException extends SsiApiException {
    public AuthenticationException(String message) { super(message, 0, null); }
    public AuthenticationException(String message, int statusCode, JsonNode responseBody) { super(message, statusCode, responseBody); }
}
