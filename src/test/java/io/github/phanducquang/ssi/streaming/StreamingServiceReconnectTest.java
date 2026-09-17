package io.github.phanducquang.ssi.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.phanducquang.ssi.auth.TokenManager;
import io.github.phanducquang.ssi.config.SsiConfig;
import io.github.phanducquang.ssi.exception.AuthenticationException;
import io.github.phanducquang.ssi.streaming.enums.StreamingMethod;
import io.github.phanducquang.ssi.streaming.model.StreamingRequest;
import io.github.phanducquang.ssi.transport.RestClient;
import io.github.phanducquang.ssi.transport.RestTransport;
import io.github.phanducquang.ssi.transport.websocket.ConnectionState;
import io.github.phanducquang.ssi.transport.websocket.WebSocketLifecycleListener;
import io.github.phanducquang.ssi.transport.websocket.WebSocketTransport;
import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingServiceReconnectTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void unexpectedDisconnectReconnectsAndReplaysSubscriptions() throws Exception {
        SsiConfig config = config();
        FakeRestTransport rest = new FakeRestTransport();
        rest.enqueue(200, tokenResponse("access-1", Instant.now().getEpochSecond() + 3600, "refresh-1"));
        TokenManager tokenManager = new TokenManager(rest, config, mapper);
        tokenManager.authenticateWithOtp("123456");
        FakeWebSocketTransport webSocket = new FakeWebSocketTransport();
        try (StreamingService streaming = service(tokenManager, webSocket, config)) {
            streaming.connectWithOtp("ignored");
            streaming.subscribeTrade("VNM", "SSI");
            assertEquals(2, streaming.activeSubscriptions().size());
            assertEquals(1, webSocket.connectCount);
            assertEquals(1, webSocket.sent.size());
            webSocket.triggerUnexpectedClose();
            await(() -> webSocket.connectCount >= 2 && webSocket.sent.size() >= 2, Duration.ofSeconds(1));
            StreamingRequest replay = (StreamingRequest) webSocket.sent.get(1);
            assertEquals(StreamingMethod.SUBSCRIBE, replay.method());
            assertEquals(List.of("trade.SSI", "trade.VNM"), replay.topics());
            assertEquals(ConnectionState.CONNECTED, streaming.connectionState());
        }
    }

    @Test
    void manualDisconnectNeverReconnects() throws Exception {
        SsiConfig config = config();
        FakeRestTransport rest = new FakeRestTransport();
        rest.enqueue(200, tokenResponse("access-1", Instant.now().getEpochSecond() + 3600, "refresh-1"));
        TokenManager tokenManager = new TokenManager(rest, config, mapper);
        tokenManager.authenticateWithOtp("123456");
        FakeWebSocketTransport webSocket = new FakeWebSocketTransport();
        try (StreamingService streaming = service(tokenManager, webSocket, config)) {
            streaming.connectWithOtp("ignored");
            streaming.subscribeTrade("VNM");
            streaming.disconnect();
            Thread.sleep(25);
            assertEquals(1, webSocket.connectCount);
            assertEquals(ConnectionState.DISCONNECTED, streaming.connectionState());
        }
    }

    @Test
    void reconnectStopsAtAuthRequiredInsteadOfStartingOtpAgain() throws Exception {
        SsiConfig config = config();
        FakeRestTransport rest = new FakeRestTransport();
        rest.enqueue(200, tokenResponse("access-server-rejected", Instant.now().getEpochSecond() + 3600, ""));
        TokenManager tokenManager = new TokenManager(rest, config, mapper);
        tokenManager.authenticateWithOtp("123456");
        FakeWebSocketTransport webSocket = new FakeWebSocketTransport();
        AtomicReference<AuthenticationException> authRequired = new AtomicReference<>();
        try (StreamingService streaming = service(tokenManager, webSocket, config)) {
            streaming.onAuthenticationRequired(authRequired::set);
            streaming.connectWithOtp("ignored");
            webSocket.failNextConnectWithAuth = true;
            webSocket.triggerUnexpectedClose();
            await(() -> streaming.connectionState() == ConnectionState.AUTH_REQUIRED, Duration.ofSeconds(1));
            assertNotNull(authRequired.get());
            assertEquals(2, webSocket.connectCount);
            assertEquals(List.of(TokenManager.ACCESS_TOKEN_PATH), rest.paths);
        }
    }

    @Test
    void unsubscribeRemovesTopicFromReconnectRegistry() throws Exception {
        SsiConfig config = config();
        FakeRestTransport rest = new FakeRestTransport();
        rest.enqueue(200, tokenResponse("access-1", Instant.now().getEpochSecond() + 3600, "refresh-1"));
        TokenManager tokenManager = new TokenManager(rest, config, mapper);
        tokenManager.authenticateWithOtp("123456");
        FakeWebSocketTransport webSocket = new FakeWebSocketTransport();
        try (StreamingService streaming = service(tokenManager, webSocket, config)) {
            streaming.connectWithOtp("ignored");
            streaming.subscribeTrade("VNM", "SSI");
            streaming.unsubscribeTrade("SSI");
            assertEquals(1, streaming.activeSubscriptions().size());
            assertTrue(streaming.activeSubscriptions().stream().anyMatch(subscription -> subscription.topic().equals("trade.VNM")));
        }
    }

    private StreamingService service(TokenManager tokenManager, WebSocketTransport webSocket, SsiConfig config) { return new StreamingService(tokenManager, webSocket, new StreamingMessageDispatcher(mapper), config); }
    private SsiConfig config() { return SsiConfig.builder().apiKey("key").apiSecret("secret").retryDelay(Duration.ofMillis(1)).maxRetries(3).build(); }
    private String tokenResponse(String accessToken, long expiresAt, String refreshToken) { return "{\"data\":{\"accessToken\":\"" + accessToken + "\",\"tokenType\":\"Bearer\",\"expiresAt\":" + expiresAt + ",\"refreshToken\":\"" + refreshToken + "\"}}"; }

    private void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Condition was not met before timeout");
            Thread.sleep(5);
        }
    }

    private final class FakeRestTransport implements RestTransport {
        private final Queue<RestClient.ApiResponse> responses = new ArrayDeque<>();
        private final List<String> paths = new ArrayList<>();
        void enqueue(int status, String body) throws Exception { responses.add(new RestClient.ApiResponse(status, mapper.readTree(body), HttpHeaders.of(Map.of(), (a, b) -> true))); }
        @Override public RestClient.ApiResponse post(String path, Object body) { paths.add(path); return responses.remove(); }
        @Override public void setAccessToken(String token) {}
        @Override public void close() {}
    }

    private static final class FakeWebSocketTransport implements WebSocketTransport {
        private final List<Object> sent = new CopyOnWriteArrayList<>();
        private volatile Consumer<String> messageHandler = ignored -> {};
        private volatile WebSocketLifecycleListener lifecycleListener = new WebSocketLifecycleListener() {};
        private volatile ConnectionState state = ConnectionState.DISCONNECTED;
        private volatile boolean connected;
        private volatile int connectCount;
        private volatile boolean failNextConnectWithAuth;
        @Override public void connect(String accessToken) {
            connectCount++;
            if (failNextConnectWithAuth) {
                failNextConnectWithAuth = false;
                state = ConnectionState.FAILED;
                throw new AuthenticationException("simulated WebSocket authentication failure");
            }
            connected = true;
            state = ConnectionState.CONNECTED;
            lifecycleListener.onConnected();
        }
        @Override public void onMessage(Consumer<String> handler) { messageHandler = handler; }
        @Override public void onLifecycle(WebSocketLifecycleListener listener) { lifecycleListener = listener; }
        @Override public void send(Object payload) { if (!connected) throw new IllegalStateException("not connected"); sent.add(payload); }
        @Override public void disconnect() { boolean wasConnected = connected; connected = false; state = ConnectionState.DISCONNECTED; if (wasConnected) lifecycleListener.onDisconnected(1000, "client closing"); }
        void triggerUnexpectedClose() { connected = false; state = ConnectionState.DISCONNECTED; lifecycleListener.onDisconnected(1006, "network lost"); }
        @Override public void awaitClose() {}
        @Override public boolean awaitClose(Duration timeout) { return !connected; }
        @Override public boolean isConnected() { return connected; }
        @Override public ConnectionState state() { return state; }
        @Override public void close() { disconnect(); }
    }
}
