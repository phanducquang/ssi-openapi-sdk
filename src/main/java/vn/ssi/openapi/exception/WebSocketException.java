package vn.ssi.openapi.exception;

public class WebSocketException extends SsiException {
    public WebSocketException(String message) { super(message); }
    public WebSocketException(String message, Throwable cause) { super(message, cause); }
}
