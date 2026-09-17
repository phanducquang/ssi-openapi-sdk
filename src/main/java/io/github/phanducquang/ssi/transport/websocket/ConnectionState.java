package io.github.phanducquang.ssi.transport.websocket;

public enum ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    AUTH_REQUIRED,
    FAILED
}
