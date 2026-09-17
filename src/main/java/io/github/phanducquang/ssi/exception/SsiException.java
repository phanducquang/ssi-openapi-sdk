package io.github.phanducquang.ssi.exception;

public class SsiException extends RuntimeException {
    public SsiException(String message) { super(message); }
    public SsiException(String message, Throwable cause) { super(message, cause); }
}
