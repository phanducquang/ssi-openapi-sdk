package vn.ssi.openapi.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.ssi.openapi.auth.TokenManager;
import vn.ssi.openapi.config.*;
import vn.ssi.openapi.exception.*;
import vn.ssi.openapi.streaming.enums.StreamingMethod;
import vn.ssi.openapi.streaming.model.*;
import vn.ssi.openapi.transport.*;
import vn.ssi.openapi.transport.websocket.*;

import java.net.http.HttpHeaders;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

class StreamingRecoveryTest {
    private static final String PONG = "{\"method\":\"ping_pong\",\"channel\":\"HEARTBEAT\",\"status\":\"ok\",\"message\":\"pong\"}";

    @Test void expiredTokensReauthenticateAndReplayAllWildcards() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.subscribeWildcards();
            f.clock.advance(Duration.ofDays(1));
            f.rest.token("new-access", "new-refresh", 3600, 7200);
            f.socket.lost();
            await(() -> f.stream.isReady() && f.socket.connects.get() == 2);
            assertEquals(List.of(TokenManager.ACCESS_TOKEN_PATH, TokenManager.ACCESS_TOKEN_PATH), f.rest.paths);
            assertEquals(Set.of("trade.*", "quote.*", "room.*", "put.*"), Set.copyOf(f.socket.sent.get(4).topics()));
            assertEquals(1, f.socket.onceConnects.get());
        }
    }

    @Test void defaultPolicyStopsWithoutImplicitAuthentication() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.clock.advance(Duration.ofDays(1));
            f.socket.lost();
            await(() -> f.stream.connectionState() == ConnectionState.AUTH_REQUIRED);
            assertEquals(RecoveryReason.NO_USABLE_TOKEN, f.stream.diagnostics().reason());
            assertEquals(List.of(TokenManager.ACCESS_TOKEN_PATH), f.rest.paths);
            assertTrue(f.stream.awaitClose(Duration.ofMillis(100)));
        }
    }

    @Test void expiredAccessUsesRefreshBeforeFreshAuthentication() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.clock.advance(Duration.ofMinutes(61));
            f.rest.token("refreshed-access", "refreshed-refresh", 3600, 7200);
            f.socket.lost();
            await(() -> f.stream.isReady() && f.socket.connects.get() == 2);
            assertEquals(List.of(TokenManager.ACCESS_TOKEN_PATH, TokenManager.REFRESH_TOKEN_PATH), f.rest.paths);
        }
    }

    @Test void refreshRejectionFallsBackOnlyWhenPolicyAllows() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.clock.advance(Duration.ofMinutes(61));
            f.rest.fail(new AuthenticationException("refresh rejected", 401, null));
            f.rest.token("fresh-access", "fresh-refresh", 3600, 7200);
            f.socket.lost();
            await(() -> f.stream.isReady() && f.socket.connects.get() == 2);
            assertEquals(List.of(TokenManager.ACCESS_TOKEN_PATH, TokenManager.REFRESH_TOKEN_PATH, TokenManager.ACCESS_TOKEN_PATH), f.rest.paths);
        }
        try (Fixture f = new Fixture(false)) {
            f.clock.advance(Duration.ofMinutes(61));
            f.rest.fail(new AuthenticationException("refresh rejected", 403, null));
            f.socket.lost();
            await(() -> f.stream.connectionState() == ConnectionState.AUTH_REQUIRED);
            assertEquals(RecoveryReason.REFRESH_REJECTED, f.stream.diagnostics().reason());
            assertEquals(403, f.stream.diagnostics().httpStatus());
            assertEquals(2, f.rest.paths.size());
        }
    }

    @Test void refresh503RetriesWithoutFreshAuthenticationOrStuckTask() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.clock.advance(Duration.ofMinutes(61));
            f.rest.fail(new SsiApiException("simulated temporary failure", 503, null));
            f.rest.token("refreshed", "refresh-next", 3600, 7200);
            AtomicReference<ConnectionDiagnostics> failure = new AtomicReference<>();
            f.stream.onConnectionError(error -> { if (f.stream.diagnostics().httpStatus() == 503) failure.set(f.stream.diagnostics()); });
            f.socket.lost();
            await(() -> f.stream.isReady() && f.socket.connects.get() == 2);
            assertNotNull(failure.get());
            assertEquals(RecoveryStage.REFRESH, failure.get().stage());
            assertEquals(List.of(TokenManager.ACCESS_TOKEN_PATH, TokenManager.REFRESH_TOKEN_PATH, TokenManager.REFRESH_TOKEN_PATH), f.rest.paths);
        }
    }

    @Test void authentication503RetriesButRejectedCredentialsStop() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.clock.advance(Duration.ofDays(1));
            f.rest.fail(new SsiApiException("temporary", 503, null));
            f.rest.token("fresh", "refresh-fresh", 3600, 7200);
            f.socket.lost();
            await(() -> f.stream.isReady() && f.socket.connects.get() == 2);
            assertEquals(3, f.rest.paths.size());
        }
        try (Fixture f = new Fixture(true)) {
            f.clock.advance(Duration.ofDays(1));
            f.rest.fail(new AuthenticationException("invalid credentials", 401, null));
            f.socket.lost();
            await(() -> f.stream.connectionState() == ConnectionState.AUTH_REQUIRED);
            assertEquals(RecoveryReason.AUTHENTICATION_REJECTED, f.stream.diagnostics().reason());
            assertEquals(RecoveryStage.AUTHENTICATE, f.stream.diagnostics().stage());
            assertEquals(2, f.rest.paths.size());
        }
    }

    @Test void smartOtpPendingIsReportedWithoutRequestingOtp() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.clock.advance(Duration.ofDays(1));
            f.rest.fail(new AuthenticationException("pending", 401, f.mapper.readTree("{\"code\":401114}")));
            f.socket.lost();
            await(() -> f.stream.connectionState() == ConnectionState.AUTH_REQUIRED);
            assertEquals(RecoveryReason.INTERACTION_REQUIRED, f.stream.diagnostics().reason());
            assertFalse(f.rest.paths.contains(TokenManager.REQUEST_OTP_PATH));
        }
    }

    @Test void serverRejectedTokenIsRefreshedAndFreshlyAuthenticatedTokenIsNotLooped() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.socket.failures.add(new AuthenticationException("handshake", 401, null));
            f.rest.token("refreshed", "refresh-next", 3600, 7200);
            f.socket.lost();
            await(() -> f.stream.isReady() && f.socket.connects.get() == 3);
            assertEquals("refreshed", f.socket.tokens.get(2));
            assertEquals(2, f.rest.paths.size());
        }
        try (Fixture f = new Fixture(true)) {
            f.clock.advance(Duration.ofDays(1));
            f.rest.token("fresh", "refresh-fresh", 3600, 7200);
            f.socket.failures.add(new AuthenticationException("handshake", 403, null));
            f.socket.lost();
            await(() -> f.stream.connectionState() == ConnectionState.AUTH_REQUIRED);
            assertEquals(RecoveryReason.HANDSHAKE_AUTH_REJECTED, f.stream.diagnostics().reason());
            assertEquals(403, f.stream.diagnostics().httpStatus());
            assertEquals(2, f.rest.paths.size());
        }
    }

    @Test void repeatedlyRejectedRefreshedTokenFallsBackOnceToCredentials() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.socket.failures.add(new AuthenticationException("reject cached", 401, null));
            f.socket.failures.add(new AuthenticationException("reject refreshed", 401, null));
            f.rest.token("refreshed", "refresh-next", 3600, 7200);
            f.rest.token("reauthenticated", "refresh-fresh", 3600, 7200);
            f.socket.lost();
            await(() -> f.stream.isReady() && f.socket.connects.get() == 4);
            assertEquals(List.of(TokenManager.ACCESS_TOKEN_PATH, TokenManager.REFRESH_TOKEN_PATH, TokenManager.ACCESS_TOKEN_PATH), f.rest.paths);
        }
    }

    @Test void restoreFailureKeepsRegistryAndReadyWaitsForReplay() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.subscribeWildcards();
            AtomicBoolean readyDuringOpen = new AtomicBoolean(true);
            f.stream.onConnected(() -> readyDuringOpen.set(f.stream.isReady()));
            f.socket.failSend.set(true);
            f.socket.lost();
            await(() -> f.stream.isReady() && f.socket.connects.get() == 3);
            assertFalse(readyDuringOpen.get());
            assertEquals(4, f.stream.activeSubscriptions().size());
            assertEquals(2, f.readyCount.get());
        }
    }

    @Test void tradingReplayRespectsUnsubscribeAndSeparatesChannels() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.stream.subscribeTrade("AAA", "BBB");
            f.stream.unsubscribeTrade("BBB");
            f.stream.subscribeOrderStatus("123");
            f.stream.subscribePortfolio("123");
            f.socket.lost();
            await(() -> f.stream.isReady() && f.socket.connects.get() == 2);
            var replay = f.socket.sent.subList(4, f.socket.sent.size());
            assertEquals(2, replay.size());
            assertTrue(replay.stream().flatMap(row -> row.topics().stream()).noneMatch("trade.BBB"::equals));
            assertTrue(replay.stream().anyMatch(row -> row.topics().equals(List.of("order.123", "portfolio.123"))));
        }
    }

    @Test void runtimeBudgetIsIndependentAndExhaustionUnblocksAwaitClose() throws Exception {
        var policy = new ReconnectPolicy(2, Duration.ofMillis(1), Duration.ofMillis(5), 0);
        try (Fixture f = new Fixture(builder().maxRetries(20).reconnectPolicy(policy))) {
            f.socket.failures.add(new WebSocketException("offline"));
            f.socket.failures.add(new WebSocketException("offline"));
            f.socket.lost();
            await(() -> f.stream.connectionState() == ConnectionState.FAILED);
            assertEquals(3, f.socket.connects.get());
            assertEquals(2, f.socket.onceConnects.get());
            assertEquals(RecoveryReason.RETRY_EXHAUSTED, f.stream.diagnostics().reason());
            assertTrue(f.stream.awaitClose(Duration.ofMillis(100)));
        }
    }

    @Test void unlimitedPolicyRecoversAfterSeveralFailuresAndCloseCancelsIt() throws Exception {
        var policy = new ReconnectPolicy(-1, Duration.ofMillis(1), Duration.ofMillis(3), 0);
        try (Fixture f = new Fixture(builder().reconnectPolicy(policy))) {
            for (int i = 0; i < 5; i++) f.socket.failures.add(new WebSocketException("offline"));
            f.socket.lost();
            await(() -> f.stream.isReady() && f.socket.connects.get() == 7);
            f.stream.close();
            assertThrows(IllegalStateException.class, f.stream::resumeAfterAuthentication);
        }
    }

    @Test void retryAfterIsHonored() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.clock.advance(Duration.ofMinutes(61));
            f.rest.fail(new RateLimitException("limited", 429, null, 0.05));
            f.rest.token("refreshed", "next-refresh", 3600, 7200);
            List<Duration> delays = new CopyOnWriteArrayList<>();
            f.stream.onReconnecting(event -> delays.add(event.delay()));
            f.socket.lost();
            await(() -> f.stream.isReady() && f.socket.connects.get() == 2);
            assertEquals(Duration.ofMillis(50), delays.get(1));
        }
    }

    @Test void malformedAndExpiredFreshTokenProduceTerminalDiagnosticsWithoutPayload() throws Exception {
        for (boolean expired : List.of(false, true)) {
            try (Fixture f = new Fixture(true)) {
                f.clock.advance(Duration.ofDays(1));
                if (expired) f.rest.token("private-access", "private-refresh", -1, 7200);
                else f.rest.raw("{\"data\":{\"refreshToken\":\"private-refresh\"}}");
                f.socket.lost();
                await(() -> f.stream.connectionState() == ConnectionState.FAILED);
                assertEquals(RecoveryReason.INVALID_TOKEN_RESPONSE, f.stream.diagnostics().reason());
                assertFalse(f.stream.diagnostics().toString().contains("private-"));
            }
        }
    }

    @Test void otpResumeReplaysRegistryAndHeartbeatContinues() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.subscribeWildcards();
            f.stream.startHeartbeat(Duration.ofMillis(10));
            f.clock.advance(Duration.ofDays(1));
            f.socket.lost();
            await(() -> f.stream.connectionState() == ConnectionState.AUTH_REQUIRED);
            f.rest.token("otp-new-access", "otp-new-refresh", 3600, 7200);
            f.tokens.authenticateWithOtp("123456");
            f.stream.resumeAfterAuthentication();
            assertTrue(f.stream.isReady());
            assertEquals(4, f.stream.activeSubscriptions().size());
            assertTrue(f.socket.sent.stream().anyMatch(row -> row.topics().size() == 4));
            await(() -> f.socket.sent.stream().filter(row -> row.method() == StreamingMethod.PING_PONG).count() >= 2);
        }
    }

    @Test void concurrentTokenResolutionRefreshesOnce() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.clock.advance(Duration.ofMinutes(61));
            f.rest.token("new-access", "new-refresh", 3600, 7200);
            ExecutorService executor = Executors.newFixedThreadPool(6);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<String>> calls = new ArrayList<>();
                for (int i = 0; i < 6; i++) calls.add(executor.submit(() -> { start.await(); return f.tokens.ensureAuthenticated(); }));
                start.countDown();
                for (var call : calls) assertEquals("new-access", call.get(1, TimeUnit.SECONDS));
                assertEquals(2, f.rest.paths.size());
                assertEquals("new-access", f.tokens.refreshAfterRejection("initial-access"));
                assertEquals(2, f.rest.paths.size());
            } finally { executor.shutdownNow(); }
        }
    }

    @Test void heartbeatValidatesPongAndTimesOutWithoutTicks() throws Exception {
        try (Fixture f = new Fixture(builder().heartbeatReceiveTimeout(Duration.ofSeconds(5)))) {
            f.stream.startHeartbeat(Duration.ofSeconds(1));
            f.socket.message("{\"channel\":\"HEARTBEAT\",\"method\":\"ping_pong\",\"status\":\"error\",\"message\":\"pong\"}");
            assertNull(f.stream.lastHeartbeatAt());
            f.socket.message(PONG);
            assertEquals(f.clock.instant(), f.stream.lastHeartbeatAt());
            f.clock.advance(Duration.ofSeconds(4));
            assertTrue(f.stream.isReady());
            f.clock.advance(Duration.ofSeconds(2));
            assertFalse(f.stream.isReady());
            f.stream.checkHeartbeat();
            await(() -> f.stream.isReady() && f.socket.connects.get() == 2);
            assertNull(f.stream.lastHeartbeatAt());
            f.socket.message(PONG);
            assertTrue(f.stream.isReady());
        }
    }

    @Test void heartbeatSendFailureTriggersRecoveryAndDisabledWatchdogDoesNot() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.socket.failSend.set(true);
            f.stream.startHeartbeat(Duration.ofMillis(5));
            await(() -> f.stream.isReady() && f.socket.connects.get() == 2);
            f.clock.advance(Duration.ofHours(10));
            assertTrue(f.stream.isReady());
        }
    }

    @Test void simultaneousCloseAndErrorScheduleOneRecovery() throws Exception {
        try (Fixture f = new Fixture(builder().reconnectPolicy(new ReconnectPolicy(5, Duration.ofMillis(30), Duration.ofMillis(30), 0)))) {
            f.socket.lost();
            f.socket.listener.onError(new WebSocketException("same connection"));
            await(() -> f.stream.isReady() && f.socket.connects.get() == 2);
            assertEquals(1, f.socket.onceConnects.get());
        }
    }

    @Test void closeInsideReconnectCallbackCancelsBeforeAuthentication() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.stream.onReconnecting(event -> f.stream.close());
            f.socket.lost();
            assertEquals(ConnectionState.DISCONNECTED, f.stream.connectionState());
            assertEquals(1, f.rest.paths.size());
            assertEquals(1, f.socket.connects.get());
        }
    }

    @Test void closeWhileRefreshIsBlockedCannotRestoreConnection() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.clock.advance(Duration.ofMinutes(61));
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            f.rest.actions.add((Supplier<Object>) () -> {
                entered.countDown();
                try { release.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new SsiApiException("interrupted", e); }
                return f.rest.response("late", "late-refresh", 3600, 7200);
            });
            f.socket.lost();
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            f.stream.close();
            release.countDown();
            assertEquals(ConnectionState.DISCONNECTED, f.stream.connectionState());
            assertEquals(1, f.socket.connects.get());
        }
    }

    @Test void ioFailureDuringRefreshRetriesAndManualResumeRequiresValidToken() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.clock.advance(Duration.ofMinutes(61));
            f.rest.fail(new SsiApiException("network", new java.io.IOException("offline")));
            f.rest.token("refreshed", "refresh-next", 3600, 7200);
            f.socket.lost();
            await(() -> f.stream.isReady() && f.socket.connects.get() == 2);
            assertEquals(3, f.rest.paths.size());
        }
        try (Fixture f = new Fixture(false)) {
            f.clock.advance(Duration.ofDays(1));
            f.socket.lost();
            await(() -> f.stream.connectionState() == ConnectionState.AUTH_REQUIRED);
            assertThrows(AuthenticationException.class, f.stream::resumeAfterAuthentication);
            assertEquals(1, f.rest.paths.size());
        }
    }

    @Test void closeDuringReplayDoesNotDeclareReadyOrLoseRegistry() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.subscribeWildcards();
            f.socket.sendEntered = new CountDownLatch(1);
            f.socket.sendRelease = new CountDownLatch(1);
            f.socket.lost();
            assertTrue(f.socket.sendEntered.await(1, TimeUnit.SECONDS));
            f.stream.close();
            f.socket.sendRelease.countDown();
            assertFalse(f.stream.isReady());
            assertEquals(ConnectionState.DISCONNECTED, f.stream.connectionState());
            assertEquals(4, f.stream.activeSubscriptions().size());
            assertEquals(1, f.readyCount.get());
        }
    }

    @Test void disabledAutoReconnectReportsHeartbeatFailureWithoutRetry() throws Exception {
        try (Fixture f = new Fixture(builder().autoReconnect(false))) {
            f.socket.failSend.set(true);
            f.stream.startHeartbeat(Duration.ofMillis(5));
            await(() -> f.stream.connectionState() == ConnectionState.FAILED);
            assertEquals(RecoveryReason.HEARTBEAT_SEND_FAILED, f.stream.diagnostics().reason());
            assertEquals(1, f.socket.connects.get());
            assertTrue(f.stream.awaitClose(Duration.ofMillis(100)));
        }
    }

    @Test void delayedHeartbeatFailureCannotDisconnectAlreadyRecoveredSession() throws Exception {
        try (Fixture f = new Fixture(builder().reconnectPolicy(new ReconnectPolicy(5, Duration.ZERO, Duration.ofMillis(5), 0)))) {
            CountDownLatch recovered = new CountDownLatch(1), nextPing = new CountDownLatch(1);
            f.stream.onReady(() -> { f.readyCount.incrementAndGet(); recovered.countDown(); });
            f.socket.beforeSendFailure = () -> {
                f.socket.connected = false;
                f.socket.listener.onError(new WebSocketException("original heartbeat send"));
                try { assertTrue(recovered.await(1, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            };
            f.socket.nextPing = nextPing;
            f.socket.failSend.set(true);
            f.stream.startHeartbeat(Duration.ofMillis(10));
            assertTrue(nextPing.await(1, TimeUnit.SECONDS));
            assertTrue(f.stream.isReady());
            assertEquals(2, f.socket.connects.get());
        }
    }

    private static SsiConfig.Builder builder() {
        return SsiConfig.builder().apiKey("test-key").apiSecret("test-secret")
                .retryDelay(Duration.ofMillis(1)).maxRetries(5).reconnectAuthenticationPolicy(ReconnectAuthenticationPolicy.API_CREDENTIALS);
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) fail("Condition timed out");
            Thread.sleep(2);
        }
    }
    private static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-13T06:00:00Z"));
        void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    }
    private static class Fixture implements AutoCloseable {
        final MutableClock clock = new MutableClock();
        final ObjectMapper mapper = new ObjectMapper();
        final Rest rest = new Rest(mapper, clock);
        final Socket socket = new Socket();
        final TokenManager tokens;
        final StreamingService stream;
        final AtomicInteger readyCount = new AtomicInteger();
        Fixture(boolean automatic) { this(builder().reconnectAuthenticationPolicy(automatic ? ReconnectAuthenticationPolicy.API_CREDENTIALS : ReconnectAuthenticationPolicy.REFRESH_ONLY)); }
        Fixture(SsiConfig.Builder builder) {
            var config = builder.build();
            rest.token("initial-access", "initial-refresh", 3600, 7200);
            tokens = new TokenManager(rest, config, mapper, clock);
            stream = new StreamingService(tokens, socket, new StreamingMessageDispatcher(mapper), config, clock);
            stream.onReady(readyCount::incrementAndGet);
            stream.connect();
        }
        void subscribeWildcards() { stream.subscribeTrade("*"); stream.subscribeQuote("*"); stream.subscribeForeignRoom("*"); stream.subscribePutThrough("*"); }
        public void close() { stream.close(); }
    }
    private static class Rest implements RestTransport {
        final ObjectMapper mapper;
        final Clock clock;
        final Queue<Object> actions = new ConcurrentLinkedQueue<>();
        final List<String> paths = new CopyOnWriteArrayList<>();
        Rest(ObjectMapper mapper, Clock clock) { this.mapper = mapper; this.clock = clock; }
        void token(String access, String refresh, long age, long refreshAge) { actions.add(response(access, refresh, age, refreshAge)); }
        RestClient.ApiResponse response(String access, String refresh, long age, long refreshAge) {
            long now = clock.instant().getEpochSecond();
            var node = mapper.valueToTree(Map.of("data", Map.of("accessToken", access, "refreshToken", refresh, "expiresAt", now + age, "refreshExpiresAt", now + refreshAge)));
            return new RestClient.ApiResponse(200, node, HttpHeaders.of(Map.of(), (a,b) -> true));
        }
        void fail(RuntimeException failure) { actions.add(failure); }
        void raw(String json) throws Exception { actions.add(new RestClient.ApiResponse(200, mapper.readTree(json), HttpHeaders.of(Map.of(), (a,b) -> true))); }
        public RestClient.ApiResponse post(String path, Object body) {
            paths.add(path);
            Object action = actions.remove();
            if (action instanceof Supplier<?> supplier) action = supplier.get();
            if (action instanceof RuntimeException failure) throw failure;
            return (RestClient.ApiResponse) action;
        }
        public void setAccessToken(String token) {}
        public void close() {}
    }
    private static class Socket implements WebSocketTransport {
        volatile boolean connected;
        volatile WebSocketLifecycleListener listener;
        volatile Consumer<String> handler;
        final AtomicInteger connects = new AtomicInteger(), onceConnects = new AtomicInteger();
        final Queue<RuntimeException> failures = new ConcurrentLinkedQueue<>();
        final AtomicBoolean failSend = new AtomicBoolean();
        volatile CountDownLatch sendEntered, sendRelease, nextPing;
        volatile Runnable beforeSendFailure;
        final List<StreamingRequest> sent = new CopyOnWriteArrayList<>();
        final List<String> tokens = new CopyOnWriteArrayList<>();
        public void connect(String token) {
            connects.incrementAndGet(); tokens.add(token);
            RuntimeException failure = failures.poll();
            if (failure != null) throw failure;
            connected = true; listener.onConnected();
        }
        public void connectOnce(String token) { onceConnects.incrementAndGet(); connect(token); }
        public void send(Object request) {
            if (sendEntered != null) {
                sendEntered.countDown();
                try { sendRelease.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new WebSocketException("replay interrupted", interrupted); }
            }
            if (failSend.getAndSet(false)) {
                Runnable hook = beforeSendFailure;
                beforeSendFailure = null;
                if (hook != null) hook.run();
                throw new WebSocketException("simulated send failure");
            }
            if (!connected) throw new WebSocketException("not connected");
            sent.add((StreamingRequest) request);
            if (nextPing != null && ((StreamingRequest) request).method() == StreamingMethod.PING_PONG) nextPing.countDown();
        }
        void lost() { connected = false; listener.onDisconnected(1006, "network"); }
        void message(String message) { handler.accept(message); }
        public void onMessage(Consumer<String> handler) { this.handler = handler; }
        public void onLifecycle(WebSocketLifecycleListener listener) { this.listener = listener; }
        public void disconnect() { connected = false; }
        public boolean isConnected() { return connected; }
        public ConnectionState state() { return connected ? ConnectionState.CONNECTED : ConnectionState.DISCONNECTED; }
        public void awaitClose() {}
        public boolean awaitClose(Duration timeout) { return !connected; }
        public void close() { disconnect(); }
    }
}
