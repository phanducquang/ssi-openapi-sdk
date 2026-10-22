package vn.ssi.openapi.transport.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.ssi.openapi.config.SsiConfig;
import vn.ssi.openapi.exception.WebSocketException;

import javax.net.ssl.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class SsiWebSocketClientTest {
    private SsiConfig config() { return SsiConfig.builder().timeout(Duration.ofMillis(50)).sendTimeout(Duration.ofMillis(20))
            .closeTimeout(Duration.ofMillis(20)).retryDelay(Duration.ZERO).maxRetries(3).build(); }
    private SsiWebSocketClient transport(Client client) { return new SsiWebSocketClient(config(), new ObjectMapper(), client); }

    @Test void oldCloseErrorAndMessagesCannotReplaceNewSession() {
        Client client = new Client();
        try (var transport = transport(client)) {
            AtomicInteger messages = new AtomicInteger(), errors = new AtomicInteger();
            transport.onMessage(message -> messages.incrementAndGet());
            transport.onLifecycle(new WebSocketLifecycleListener() { public void onError(Throwable error) { errors.incrementAndGet(); } });
            transport.connectOnce("old");
            var old = client.sessions.get(0);
            transport.disconnect();
            transport.connectOnce("new");
            var current = client.sessions.get(1);
            old.listener.onClose(old.socket, 1006, "late close");
            old.listener.onError(old.socket, new IllegalStateException("late error"));
            old.listener.onText(old.socket, "old message", true);
            assertTrue(transport.isConnected());
            assertEquals(0, errors.get());
            assertEquals(0, messages.get());
            current.listener.onText(current.socket, "new message", true);
            assertEquals(1, messages.get());
        }
    }

    @Test void pendingHandshakeHasDeadlineAndIsCancelled() {
        Client client = new Client(); client.openAutomatically = false;
        try (var transport = transport(client)) {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(WebSocketException.class, () -> transport.connectOnce("access")));
            var session = client.sessions.get(0);
            assertTrue(session.future.isCancelled());
            session.listener.onOpen(session.socket);
            assertTrue(session.socket.aborted);
            assertFalse(transport.isConnected());
            assertEquals(ConnectionState.FAILED, transport.state());
        }
    }

    @Test void closeDuringHandshakePreventsLateConnectedSession() throws Exception {
        Client client = new Client(); client.openAutomatically = false;
        var transport = transport(client);
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> connect = executor.submit(() -> assertThrows(CancellationException.class, () -> transport.connectOnce("access")));
            assertTrue(client.created.await(1, TimeUnit.SECONDS));
            transport.close();
            connect.get(1, TimeUnit.SECONDS);
            var session = client.sessions.get(0);
            session.listener.onOpen(session.socket);
            assertTrue(session.socket.aborted);
            assertFalse(transport.isConnected());
            assertEquals(ConnectionState.DISCONNECTED, transport.state());
        } finally { transport.close(); executor.shutdownNow(); }
    }

    @Test void closeInsideConnectedCallbackCancelsWithoutResurrectingSocket() {
        Client client = new Client();
        var transport = transport(client);
        transport.onLifecycle(new WebSocketLifecycleListener() { public void onConnected() { transport.close(); } });
        assertThrows(CancellationException.class, () -> transport.connectOnce("access"));
        assertFalse(transport.isConnected());
        assertTrue(client.sessions.get(0).socket.aborted);
    }

    @Test void sendTimeoutAbortsOnlyFailedSessionAndEmitsErrorOnce() {
        Client client = new Client();
        try (var transport = transport(client)) {
            AtomicInteger errors = new AtomicInteger();
            transport.onLifecycle(new WebSocketLifecycleListener() { public void onError(Throwable error) { errors.incrementAndGet(); } });
            transport.connectOnce("access");
            var old = client.sessions.get(0);
            old.socket.blockSend = true;
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(WebSocketException.class, () -> transport.send(Map.of("method", "ping_pong"))));
            assertTrue(old.socket.aborted);
            assertFalse(transport.isConnected());
            assertEquals(1, errors.get());
            transport.connectOnce("next");
            old.listener.onError(old.socket, new IllegalStateException("late"));
            assertEquals(1, errors.get());
            assertTrue(transport.isConnected());
        }
    }

    @Test void closeTimeoutAbortsAndOldCloseDoesNotAffectNextSession() {
        Client client = new Client();
        var transport = transport(client);
        transport.connectOnce("access");
        var old = client.sessions.get(0); old.socket.blockClose = true;
        assertTimeoutPreemptively(Duration.ofSeconds(1), transport::disconnect);
        assertTrue(old.socket.aborted);
        transport.connectOnce("next");
        old.listener.onClose(old.socket, 1000, "late");
        assertTrue(transport.isConnected());
        transport.close();
    }

    @Test void runtimeConnectOnceDoesNotMultiplyInitialRetryBudget() {
        Client onceClient = new Client(); onceClient.failConnections = true;
        try (var transport = transport(onceClient)) {
            assertThrows(WebSocketException.class, () -> transport.connectOnce("access"));
            assertEquals(1, onceClient.sessions.size());
        }
        Client initialClient = new Client(); initialClient.failConnections = true;
        try (var transport = transport(initialClient)) {
            assertThrows(WebSocketException.class, () -> transport.connect("access"));
            assertEquals(3, initialClient.sessions.size());
        }
    }

    @Test void pongAndPingContinueDemandAndPingIsAnswered() {
        Client client = new Client();
        try (var transport = transport(client)) {
            transport.connectOnce("access");
            var session = client.sessions.get(0);
            session.listener.onPong(session.socket, ByteBuffer.allocate(0));
            session.listener.onPing(session.socket, ByteBuffer.allocate(0));
            assertEquals(3, session.socket.demand.get());
            assertEquals(1, session.socket.pongs.get());
        }
    }

    @Test void unexpectedCloseBeforeHandshakeFutureCompletesIsRetryable() throws Exception {
        Client client = new Client(); client.openAutomatically = false;
        var transport = new SsiWebSocketClient(SsiConfig.builder().timeout(Duration.ofSeconds(2)).build(), new ObjectMapper(), client);
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> connect = executor.submit(() -> assertThrows(WebSocketException.class, () -> transport.connectOnce("access")));
            assertTrue(client.created.await(1, TimeUnit.SECONDS));
            var session = client.sessions.get(0);
            session.listener.onOpen(session.socket);
            session.listener.onClose(session.socket, 1006, "connection lost immediately");
            session.future.complete(session.socket);
            connect.get(1, TimeUnit.SECONDS);
            assertFalse(transport.isConnected());
            assertTrue(session.socket.aborted);
        } finally { transport.close(); executor.shutdownNow(); }
    }

    private record Session(WebSocket.Listener listener, Socket socket, CompletableFuture<WebSocket> future) {}
    private static class Client extends HttpClient {
        volatile boolean openAutomatically = true, failConnections;
        final List<Session> sessions = new CopyOnWriteArrayList<>();
        final CountDownLatch created = new CountDownLatch(1);
        public WebSocket.Builder newWebSocketBuilder() {
            return new WebSocket.Builder() {
                public WebSocket.Builder header(String name, String value) { return this; }
                public WebSocket.Builder connectTimeout(Duration timeout) { return this; }
                public WebSocket.Builder subprotocols(String first, String... others) { return this; }
                public CompletableFuture<WebSocket> buildAsync(URI uri, WebSocket.Listener listener) {
                    Socket socket = new Socket();
                    CompletableFuture<WebSocket> future = new CompletableFuture<>();
                    sessions.add(new Session(listener, socket, future));
                    created.countDown();
                    if (failConnections) future.completeExceptionally(new java.io.IOException("simulated offline"));
                    else if (openAutomatically) { listener.onOpen(socket); future.complete(socket); }
                    return future;
                }
            };
        }
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        public Optional<Duration> connectTimeout() { return Optional.empty(); }
        public Redirect followRedirects() { return Redirect.NEVER; }
        public Optional<ProxySelector> proxy() { return Optional.empty(); }
        public SSLContext sslContext() { throw new UnsupportedOperationException(); }
        public SSLParameters sslParameters() { return new SSLParameters(); }
        public Optional<Authenticator> authenticator() { return Optional.empty(); }
        public Version version() { return Version.HTTP_1_1; }
        public Optional<Executor> executor() { return Optional.empty(); }
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) { throw new UnsupportedOperationException(); }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) { throw new UnsupportedOperationException(); }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) { throw new UnsupportedOperationException(); }
    }
    private static class Socket implements WebSocket {
        volatile boolean aborted, blockSend, blockClose;
        final AtomicInteger demand = new AtomicInteger(), pongs = new AtomicInteger();
        public CompletableFuture<WebSocket> sendText(CharSequence text, boolean last) { return blockSend ? new CompletableFuture<>() : CompletableFuture.completedFuture(this); }
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer bytes, boolean last) { return CompletableFuture.completedFuture(this); }
        public CompletableFuture<WebSocket> sendPing(ByteBuffer bytes) { return CompletableFuture.completedFuture(this); }
        public CompletableFuture<WebSocket> sendPong(ByteBuffer bytes) { pongs.incrementAndGet(); return CompletableFuture.completedFuture(this); }
        public CompletableFuture<WebSocket> sendClose(int code, String reason) { return blockClose ? new CompletableFuture<>() : CompletableFuture.completedFuture(this); }
        public void request(long n) { demand.addAndGet((int) n); }
        public String getSubprotocol() { return ""; }
        public boolean isOutputClosed() { return aborted; }
        public boolean isInputClosed() { return aborted; }
        public void abort() { aborted = true; }
    }
}
