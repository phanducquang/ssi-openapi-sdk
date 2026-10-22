package vn.ssi.openapi.exception;

/** The token endpoint returned an unusable payload. Does not expose that payload. */
public final class InvalidTokenException extends SsiApiException {
    public InvalidTokenException() { super("SSI token response is invalid or expired", 0, null); }
}
