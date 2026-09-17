package io.github.phanducquang.ssi.transport.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.phanducquang.ssi.config.SsiConfig;
import io.github.phanducquang.ssi.exception.AuthenticationException;
import io.github.phanducquang.ssi.exception.RateLimitException;
import io.github.phanducquang.ssi.exception.WebSocketException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public final class SsiWebSocketClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SsiWebSocketClient.class);
    private final SsiConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private volatile WebSocket webSocket;
    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private volatile Consumer<String> messageHandler = ignored -> {};
    private volatile CountDownLatch closeLatch = new CountDownLatch(0);

    public SsiWebSocketClient(SsiConfig config, ObjectMapper objectMapper) {
        this.config = Objects.requireNonNull(config);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.httpClient = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
    }

    public synchronized void connect(String accessToken) {
        if (state == ConnectionState.CONNECTED) return;
        if (accessToken == null || accessToken.isBlank()) throw new AuthenticationException("A valid SSI access token is required before WebSocket connect");

        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < config.maxRetries(); attempt++) {
            state = ConnectionState.CONNECTING;
            closeLatch = new CountDownLatch(1);
            try {
                this.webSocket = httpClient.newWebSocketBuilder()
                        .connectTimeout(config.timeout())
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + accessToken)
                        .buildAsync(config.streamingUrl(), new Listener())
                        .join();
                state = ConnectionState.CONNECTED;
                log.info("Connected to SSI WebSocket {}", config.streamingUrl());
                return;
            } catch (CompletionException e) {
                lastFailure = mapConnectFailure(e.getCause() == null ? e : e.getCause());
                state = ConnectionState.FAILED;
                if (lastFailure instanceof AuthenticationException || lastFailure instanceof RateLimitException) throw lastFailure;
                if (attempt < config.maxRetries() - 1) sleepBackoff(attempt);
            }
        }
        throw lastFailure == null ? new WebSocketException("Failed to connect to SSI WebSocket") : lastFailure;
    }

    public void onMessage(Consumer<String> handler) { this.messageHandler = Objects.requireNonNull(handler); }

    public void send(Object payload) {
        WebSocket socket = webSocket;
        if (socket == null || state != ConnectionState.CONNECTED) throw new WebSocketException("SSI WebSocket is not connected");
        try { socket.sendText(objectMapper.writeValueAsString(payload), true).join(); }
        catch (JsonProcessingException e) { throw new WebSocketException("Failed to serialize SSI WebSocket request", e); }
        catch (CompletionException e) { throw new WebSocketException("Failed to send SSI WebSocket request", e.getCause() == null ? e : e.getCause()); }
    }

    public synchronized void disconnect() {
        WebSocket socket = webSocket;
        webSocket = null;
        if (socket != null) {
            try { socket.sendClose(WebSocket.NORMAL_CLOSURE, "client closing").join(); }
            catch (RuntimeException ignored) { socket.abort(); }
        }
        state = ConnectionState.DISCONNECTED;
        closeLatch.countDown();
    }

    public void awaitClose() {
        try { closeLatch.await(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new WebSocketException("Interrupted while waiting for SSI WebSocket to close", e); }
    }

    public boolean awaitClose(Duration timeout) {
        try { return closeLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new WebSocketException("Interrupted while waiting for SSI WebSocket to close", e); }
    }

    public boolean isConnected() { return state == ConnectionState.CONNECTED && webSocket != null; }
    public ConnectionState state() { return state; }
    @Override public void close() { disconnect(); }

    private RuntimeException mapConnectFailure(Throwable cause) {
        if (cause instanceof WebSocketHandshakeException handshake) {
            int status = handshake.getResponse().statusCode();
            if (status == 401 || status == 403) return new AuthenticationException("SSI WebSocket authentication failed: HTTP " + status, status, null);
            if (status == 429) {
                Double retryAfter = handshake.getResponse().headers().firstValue("Retry-After").map(v -> { try { return Double.parseDouble(v); } catch (NumberFormatException ignored) { return null; } }).orElse(null);
                return new RateLimitException("SSI WebSocket rate limit exceeded", status, null, retryAfter);
            }
            return new WebSocketException("SSI WebSocket handshake failed: HTTP " + status, cause);
        }
        if (cause instanceof TimeoutException) return new WebSocketException("Timed out connecting to SSI WebSocket", cause);
        return new WebSocketException("Failed to connect to SSI WebSocket: " + cause.getMessage(), cause);
    }

    private void sleepBackoff(int attempt) {
        long multiplier = 1L << Math.min(attempt, 20);
        long millis = Math.max(0L, config.retryDelay().toMillis() * multiplier);
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new WebSocketException("Interrupted during WebSocket retry backoff", e); }
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder textBuffer = new StringBuilder();
        @Override public void onOpen(WebSocket webSocket) { state = ConnectionState.CONNECTED; webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            synchronized (textBuffer) {
                textBuffer.append(data);
                if (last) {
                    String message = textBuffer.toString();
                    textBuffer.setLength(0);
                    try { messageHandler.accept(message); } catch (RuntimeException e) { log.error("Unhandled SSI streaming message callback error", e); }
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) { webSocket.request(1); return WebSocket.Listener.super.onPing(webSocket, message); }
        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            state = ConnectionState.DISCONNECTED;
            SsiWebSocketClient.this.webSocket = null;
            closeLatch.countDown();
            log.info("SSI WebSocket closed. code={}, reason={}", statusCode, reason);
            return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket webSocket, Throwable error) {
            state = ConnectionState.FAILED;
            SsiWebSocketClient.this.webSocket = null;
            closeLatch.countDown();
            log.error("SSI WebSocket error", error);
        }
    }
}
