package io.github.phanducquang.ssi.exception;

public class WebSocketException extends SsiException {
    public WebSocketException(String message) { super(message); }
    public WebSocketException(String message, Throwable cause) { super(message, cause); }
}
