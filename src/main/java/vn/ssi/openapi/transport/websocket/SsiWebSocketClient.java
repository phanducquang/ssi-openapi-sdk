package vn.ssi.openapi.transport.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import vn.ssi.openapi.config.SsiConfig;
import vn.ssi.openapi.transport.RetryAfter;
import vn.ssi.openapi.exception.AuthenticationException;
import vn.ssi.openapi.exception.RateLimitException;
import vn.ssi.openapi.exception.WebSocketException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** JDK transport with bounded I/O and generation-scoped callbacks. */
public final class SsiWebSocketClient implements WebSocketTransport {
    private static final Logger log = LoggerFactory.getLogger(SsiWebSocketClient.class);
    private final SsiConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Object sessionLock = new Object();
    private final ReentrantLock connectLock = new ReentrantLock();
    private volatile WebSocket webSocket;
    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private volatile Consumer<String> messageHandler = ignored -> {};
    private volatile WebSocketLifecycleListener lifecycleListener = new WebSocketLifecycleListener() {};
    private volatile CountDownLatch closeLatch = new CountDownLatch(0);
    private long generation;
    private volatile boolean manuallyDisconnected = true;
    private CompletableFuture<WebSocket> pendingConnect;

    public SsiWebSocketClient(SsiConfig config, ObjectMapper objectMapper) {
        this(config, objectMapper, HttpClient.newBuilder().connectTimeout(config.timeout()).build());
    }

    SsiWebSocketClient(SsiConfig config, ObjectMapper objectMapper, HttpClient httpClient) {
        this.config = Objects.requireNonNull(config);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.httpClient = Objects.requireNonNull(httpClient);
    }

    @Override public void connect(String accessToken) { connect(accessToken, config.maxRetries()); }
    @Override public void connectOnce(String accessToken) { connect(accessToken, 1); }

    private void connect(String accessToken, int attempts) {
        if (accessToken == null || accessToken.isBlank()) throw new AuthenticationException("A valid SSI access token is required before WebSocket connect");
        try { connectLock.lockInterruptibly(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new WebSocketException("SSI connect interrupted", e); }
        try {
            if (isConnected()) return;
            RuntimeException lastFailure = null;
            for (int attempt = 0; attempt < attempts; attempt++) {
                try { connectAttempt(accessToken); return; }
                catch (RuntimeException failure) {
                    lastFailure = failure;
                    if (failure instanceof AuthenticationException || failure instanceof RateLimitException
                            || failure instanceof CancellationException || Thread.currentThread().isInterrupted()) throw failure;
                    if (attempt < attempts - 1) sleepBackoff(attempt);
                }
            }
            throw lastFailure;
        } finally { connectLock.unlock(); }
    }

    private void connectAttempt(String accessToken) {
        final long session;
        synchronized (sessionLock) {
            session = ++generation;
            manuallyDisconnected = false;
            state = ConnectionState.CONNECTING;
            closeLatch = new CountDownLatch(1);
        }
        final CompletableFuture<WebSocket> future;
        try {
            future = httpClient.newWebSocketBuilder().connectTimeout(config.timeout())
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .header("Authorization", "Bearer " + accessToken)
                    .buildAsync(config.streamingUrl(), new Listener(session));
        } catch (RuntimeException failure) {
            synchronized (sessionLock) {
                if (generation == session) { ++generation; state = ConnectionState.FAILED; closeLatch.countDown(); }
            }
            throw failure;
        }
        synchronized (sessionLock) {
            if (generation != session) {
                cancelConnect(future);
                throw supersededConnect();
            }
            pendingConnect = future;
        }
        try {
            WebSocket socket = future.get(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
            synchronized (sessionLock) {
                if (generation != session || state != ConnectionState.CONNECTED) {
                    socket.abort();
                    throw supersededConnect();
                }
                webSocket = socket;
                pendingConnect = null;
            }
            log.info("Connected to SSI WebSocket");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            discardSession(session, future);
            throw new WebSocketException("SSI connect interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            discardSession(session, future);
            throw mapConnectFailure(e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e);
        } catch (RuntimeException e) {
            discardSession(session, future);
            throw e;
        }
    }

    private void discardSession(long session, CompletableFuture<WebSocket> future) {
        WebSocket socket = null;
        synchronized (sessionLock) {
            if (generation == session) {
                ++generation;
                socket = webSocket;
                webSocket = null;
                pendingConnect = null;
                state = ConnectionState.FAILED;
                closeLatch.countDown();
            }
        }
        cancelConnect(future);
        if (socket != null) socket.abort();
    }

    private void cancelConnect(CompletableFuture<WebSocket> future) {
        future.thenAccept(WebSocket::abort);
        future.cancel(true);
    }

    @Override public void onMessage(Consumer<String> handler) { messageHandler = Objects.requireNonNull(handler); }
    @Override public void onLifecycle(WebSocketLifecycleListener listener) { lifecycleListener = Objects.requireNonNull(listener); }

    @Override public void send(Object payload) {
        final WebSocket socket;
        final long session;
        synchronized (sessionLock) {
            socket = webSocket;
            session = generation;
            if (socket == null || state != ConnectionState.CONNECTED) throw new WebSocketException("SSI WebSocket is not connected");
        }
        try {
            socket.sendText(objectMapper.writeValueAsString(payload), true).get(config.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (JsonProcessingException e) {
            throw new WebSocketException("Failed to serialize SSI WebSocket request", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failSession(session, socket, e);
            throw new WebSocketException("SSI WebSocket send interrupted", e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            failSession(session, socket, cause);
            throw new WebSocketException("Failed to send SSI WebSocket request", cause);
        }
    }

    @Override public void disconnect() {
        final WebSocket socket;
        final CompletableFuture<WebSocket> future;
        synchronized (sessionLock) {
            ++generation;
            manuallyDisconnected = true;
            socket = webSocket;
            future = pendingConnect;
            webSocket = null;
            pendingConnect = null;
            state = ConnectionState.DISCONNECTED;
            closeLatch.countDown();
        }
        if (future != null) cancelConnect(future);
        if (socket != null) {
            try { socket.sendClose(WebSocket.NORMAL_CLOSURE, "client closing").get(config.closeTimeout().toMillis(), TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); socket.abort(); }
            catch (ExecutionException | TimeoutException | RuntimeException e) { socket.abort(); }
            finally { socket.abort(); }
        }
    }

    @Override public void awaitClose() {
        try { closeLatch.await(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new WebSocketException("SSI awaitClose interrupted", e); }
    }
    @Override public boolean awaitClose(Duration timeout) {
        try { return closeLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new WebSocketException("SSI awaitClose interrupted", e); }
    }
    @Override public boolean isConnected() { return state == ConnectionState.CONNECTED && webSocket != null; }
    @Override public ConnectionState state() { return state; }
    @Override public void close() { disconnect(); }

    private RuntimeException supersededConnect() {
        return manuallyDisconnected ? new CancellationException("SSI connect cancelled by disconnect")
                : new WebSocketException("SSI connection ended during handshake");
    }

    private RuntimeException mapConnectFailure(Throwable cause) {
        if (cause instanceof WebSocketHandshakeException handshake) {
            int status = handshake.getResponse().statusCode();
            if (status == 401 || status == 403) return new AuthenticationException("SSI WebSocket authentication failed", status, null);
            if (status == 429) {
                Double retryAfter = RetryAfter.seconds(handshake.getResponse().headers(), java.time.Instant.now());
                return new RateLimitException("SSI WebSocket rate limited", status, null, retryAfter);
            }
            return new WebSocketException("SSI WebSocket handshake failed: HTTP " + status, cause);
        }
        return new WebSocketException("SSI WebSocket connect failed", cause);
    }

    private void sleepBackoff(int attempt) {
        long base = config.retryDelay().toMillis();
        long factor = 1L << Math.min(attempt, 20);
        long millis = base > Long.MAX_VALUE / factor ? Long.MAX_VALUE : base * factor;
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new WebSocketException("SSI retry interrupted", e); }
    }

    private boolean current(long session) { synchronized (sessionLock) { return generation == session; } }

    private void failSession(long session, WebSocket socket, Throwable cause) {
        synchronized (sessionLock) {
            if (generation != session) return;
            ++generation;
            state = ConnectionState.FAILED;
            webSocket = null;
            closeLatch.countDown();
        }
        socket.abort();
        try { lifecycleListener.onError(cause); }
        catch (RuntimeException e) { log.warn("SSI transport error callback failed: type={}", e.getClass().getSimpleName()); }
    }

    private final class Listener implements WebSocket.Listener {
        private final long session;
        private final StringBuilder textBuffer = new StringBuilder();
        private Listener(long session) { this.session = session; }
        @Override public void onOpen(WebSocket socket) {
            synchronized (sessionLock) {
                if (generation != session) { socket.abort(); return; }
                webSocket = socket;
                state = ConnectionState.CONNECTED;
            }
            try { lifecycleListener.onConnected(); }
            catch (RuntimeException e) { log.warn("SSI connected callback failed: type={}", e.getClass().getSimpleName()); }
            socket.request(1);
        }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            if (!current(session)) return CompletableFuture.completedFuture(null);
            synchronized (textBuffer) {
                textBuffer.append(data);
                if (last) {
                    String message = textBuffer.toString();
                    textBuffer.setLength(0);
                    if (current(session)) {
                        try { messageHandler.accept(message); }
                        catch (RuntimeException e) { log.warn("SSI message callback failed: type={}", e.getClass().getSimpleName()); }
                    }
                }
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onPing(WebSocket socket, ByteBuffer message) {
            if (!current(session)) return CompletableFuture.completedFuture(null);
            socket.request(1);
            return socket.sendPong(message);
        }
        @Override public CompletionStage<?> onPong(WebSocket socket, ByteBuffer message) {
            if (current(session)) socket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            synchronized (sessionLock) {
                if (generation != session) return CompletableFuture.completedFuture(null);
                ++generation;
                state = ConnectionState.DISCONNECTED;
                webSocket = null;
                closeLatch.countDown();
            }
            log.info("SSI WebSocket closed: code={}", statusCode);
            try { lifecycleListener.onDisconnected(statusCode, reason); }
            catch (RuntimeException e) { log.warn("SSI disconnected callback failed: type={}", e.getClass().getSimpleName()); }
            return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket socket, Throwable error) { failSession(session, socket, error); }
    }
}
