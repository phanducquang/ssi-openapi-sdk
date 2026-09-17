package io.github.phanducquang.ssi.transport.websocket;

public interface WebSocketLifecycleListener {
    default void onConnected() {}
    default void onDisconnected(int statusCode, String reason) {}
    default void onError(Throwable error) {}
}
