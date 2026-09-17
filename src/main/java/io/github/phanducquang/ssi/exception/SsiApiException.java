package io.github.phanducquang.ssi.exception;

import com.fasterxml.jackson.databind.JsonNode;

public class SsiApiException extends SsiException {
    private final int statusCode;
    private final JsonNode responseBody;

    public SsiApiException(String message, int statusCode, JsonNode responseBody) { super(message); this.statusCode = statusCode; this.responseBody = responseBody; }
    public SsiApiException(String message, Throwable cause) { super(message, cause); this.statusCode = 0; this.responseBody = null; }
    public int statusCode() { return statusCode; }
    public JsonNode responseBody() { return responseBody; }
}
