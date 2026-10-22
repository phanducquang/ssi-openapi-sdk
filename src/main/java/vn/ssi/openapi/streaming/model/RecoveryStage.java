package vn.ssi.openapi.streaming.model;

public enum RecoveryStage {
    NONE, TOKEN_LOOKUP, REFRESH, AUTHENTICATE, HANDSHAKE, RESTORE_SUBSCRIPTIONS, HEARTBEAT
}
