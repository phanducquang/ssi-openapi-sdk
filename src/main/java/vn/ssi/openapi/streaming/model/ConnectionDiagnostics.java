package vn.ssi.openapi.streaming.model;

import vn.ssi.openapi.auth.model.TokenDiagnostics;
import vn.ssi.openapi.transport.websocket.ConnectionState;
import java.time.Instant;

/** A snapshot containing no credentials, tokens or server authentication payloads. */
public record ConnectionDiagnostics(ConnectionState state, boolean ready, int attempt, Instant changedAt,
                                    Instant lastMessageAt, Instant lastHeartbeatAt, RecoveryStage stage,
                                    RecoveryReason reason, String errorType, int httpStatus, TokenDiagnostics token) {}
