package vn.ssi.openapi.exception;

import vn.ssi.openapi.streaming.model.RecoveryReason;
import vn.ssi.openapi.streaming.model.RecoveryStage;

/** Sanitized terminal authentication failure. Existing authentication callbacks remain compatible. */
public final class ReconnectAuthenticationException extends AuthenticationException {
    private final RecoveryStage stage;
    private final RecoveryReason reason;

    public ReconnectAuthenticationException(RecoveryStage stage, RecoveryReason reason, int status) {
        super("SSI reconnect authentication required: " + reason, status, null);
        this.stage = stage;
        this.reason = reason;
    }
    public RecoveryStage stage() { return stage; }
    public RecoveryReason reason() { return reason; }
}
