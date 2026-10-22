package vn.ssi.openapi.auth.model;

/** Token metadata only; safe to log. Zero expiry means the server did not provide an expiry. */
public record TokenDiagnostics(boolean accessPresent, boolean accessValid, long expiresAt,
                               boolean refreshPresent, boolean refreshValid, long refreshExpiresAt) {}
