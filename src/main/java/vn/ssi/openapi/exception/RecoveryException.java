package vn.ssi.openapi.exception;

import vn.ssi.openapi.streaming.model.RecoveryReason;
import vn.ssi.openapi.streaming.model.RecoveryStage;

/** Identifies a recovery stage without copying server response bodies into diagnostics. */
public final class RecoveryException extends SsiApiException {
    private final RecoveryStage stage;
    private final RecoveryReason reason;

    public RecoveryException(RecoveryStage stage, RecoveryReason reason, RuntimeException cause) {
        super("SSI recovery failed: " + reason, cause instanceof SsiApiException api ? api.statusCode() : 0, null);
        this.stage = stage;
        this.reason = reason;
        initCause(cause);
    }
    public RecoveryStage stage() { return stage; }
    public RecoveryReason reason() { return reason; }
}
