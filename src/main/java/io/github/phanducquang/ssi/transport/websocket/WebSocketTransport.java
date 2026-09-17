package io.github.phanducquang.ssi.transport.websocket;

import java.time.Duration;
import java.util.function.Consumer;

public interface WebSocketTransport extends AutoCloseable {
    void connect(String accessToken);
    void onMessage(Consumer<String> handler);
    void onLifecycle(WebSocketLifecycleListener listener);
    void send(Object payload);
    void disconnect();
    void awaitClose();
    boolean awaitClose(Duration timeout);
    boolean isConnected();
    ConnectionState state();
    @Override void close();
}
