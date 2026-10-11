package vn.ssi.openapi.transport.websocket;

public enum ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    AUTH_REQUIRED,
    FAILED
}
