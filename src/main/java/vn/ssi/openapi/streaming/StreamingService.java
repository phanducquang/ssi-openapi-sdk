package vn.ssi.openapi.streaming;

import com.fasterxml.jackson.databind.JsonNode;
import vn.ssi.openapi.auth.TokenManager;
import vn.ssi.openapi.config.SsiConfig;
import vn.ssi.openapi.exception.*;
import vn.ssi.openapi.marketdata.enums.Board;
import vn.ssi.openapi.streaming.enums.*;
import vn.ssi.openapi.streaming.model.*;
import vn.ssi.openapi.transport.websocket.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.WebSocket;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class StreamingService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(StreamingService.class);
    private final TokenManager tokenManager;
    private final WebSocketTransport webSocket;
    private final StreamingMessageDispatcher dispatcher;
    private final SsiConfig config;
    private final Clock clock;
    private final Object lifecycleLock = new Object();
    private final Object subscriptionLock = new Object();
    private final ReentrantLock connectLock = new ReentrantLock();
    private final Set<StreamingSubscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService reconnectExecutor;
    private volatile ScheduledExecutorService heartbeatExecutor;
    private volatile ScheduledFuture<?> reconnectFuture;
    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private volatile CountDownLatch sessionCloseLatch = new CountDownLatch(0);
    private volatile Instant lastMessageAt;
    private volatile Instant lastHeartbeatAt;
    private volatile Instant connectedAt;
    private volatile Instant changedAt;
    private volatile boolean manualDisconnect = true;
    private volatile boolean reconnectEnabled;
    private volatile boolean closed;
    private volatile boolean ready;
    private volatile boolean resettingTransport;
    private volatile int currentAttempt;
    private volatile RecoveryStage failureStage = RecoveryStage.NONE;
    private volatile RecoveryReason failureReason = RecoveryReason.NONE;
    private volatile String errorType;
    private volatile int errorStatus;
    private long lifecycleEpoch;
    private boolean recovering;
    private boolean reauthenticated;
    private int rejectedHandshakes;
    private String rejectedToken;
    private volatile Runnable connectedListener = () -> {};
    private volatile Runnable readyListener = () -> {};
    private volatile Consumer<DisconnectEvent> disconnectedListener = ignored -> {};
    private volatile Consumer<ReconnectEvent> reconnectingListener = ignored -> {};
    private volatile Consumer<AuthenticationException> authenticationRequiredListener = ignored -> {};
    private volatile Consumer<Throwable> connectionErrorListener = ignored -> {};

    public StreamingService(TokenManager tokenManager, WebSocketTransport webSocket, StreamingMessageDispatcher dispatcher, SsiConfig config) {
        this(tokenManager, webSocket, dispatcher, config, Clock.systemUTC());
    }

    StreamingService(TokenManager tokenManager, WebSocketTransport webSocket, StreamingMessageDispatcher dispatcher, SsiConfig config, Clock clock) {
        this.tokenManager = Objects.requireNonNull(tokenManager);
        this.webSocket = Objects.requireNonNull(webSocket);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.config = Objects.requireNonNull(config);
        this.clock = Objects.requireNonNull(clock);
        this.changedAt = clock.instant();
        this.reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "ssi-websocket-reconnect"));
        this.webSocket.onMessage(message -> {
            if (manualDisconnect || closed) return;
            lastMessageAt = clock.instant();
            dispatcher.dispatch(message);
        });
        dispatcher.onHeartbeat(message -> {
            if (isConnected() && config.heartbeatResponseValidator().test(message)) lastHeartbeatAt = clock.instant();
        });
        this.webSocket.onLifecycle(new LifecycleListener());
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    public void connect() { connectInternal(tokenManager::ensureAuthenticated); }
    public void connectWithOtp(String otp) { connectInternal(() -> tokenManager.ensureAuthenticatedWithOtp(otp)); }
    public void connectWithSmartOtp(String transactionId) { connectInternal(() -> tokenManager.ensureAuthenticatedWithSmartOtp(transactionId)); }
    public void connectWithSmartOtpApproval() { connectInternal(tokenManager::ensureAuthenticatedWithSmartOtpApproval); }
    public void subscribeTrade(String... symbols) { subscribe(DataTopic.TRADE, Arrays.asList(symbols)); }
    public void subscribeQuote(String... symbols) { subscribe(DataTopic.QUOTE, Arrays.asList(symbols)); }
    public void subscribeForeignRoom(String... symbols) { subscribe(DataTopic.ROOM, Arrays.asList(symbols)); }
    public void subscribeMarketStatus(String... markets) { subscribe(DataTopic.MARKET, Arrays.asList(markets)); }
    public void subscribePutThrough(String... symbols) { subscribe(DataTopic.PUT, Arrays.asList(symbols)); }
    public void subscribeOddLot(String... symbols) { subscribe(DataTopic.ODD_LOT, Arrays.asList(symbols)); }
    public void subscribeSymbol(String... symbols) { subscribeTrade(symbols); subscribeQuote(symbols); subscribeForeignRoom(symbols); }
    public void subscribeBoard(Board... boards) {
        Objects.requireNonNull(boards, "boards");
        subscribeSymbol(Arrays.stream(boards).filter(Objects::nonNull).map(Board::value).toArray(String[]::new));
    }
    public void subscribeIndex(String... indices) { subscribeSymbol(indices); }
    public void subscribeOrderStatus() { subscribeOrderStatus("*"); }
    public void subscribeOrderStatus(String accountNo) { sendTracked(StreamingMethod.SUBSCRIBE, StreamingChannel.TRADING, List.of(tradingTopic("order", accountNo))); }
    public void subscribePortfolio() { subscribePortfolio("*"); }
    public void subscribePortfolio(String accountNo) { sendTracked(StreamingMethod.SUBSCRIBE, StreamingChannel.TRADING, List.of(tradingTopic("portfolio", accountNo))); }

    public void subscribeOhlcv(Timeframe timeframe, String... symbols) {
        Objects.requireNonNull(timeframe, "timeframe");
        List<String> topics = normalized(symbols).stream().map(symbol -> "trade." + symbol + "@" + timeframe.value()).toList();
        sendTracked(StreamingMethod.SUBSCRIBE, StreamingChannel.DATA, topics);
    }

    public void unsubscribeTrade(String... symbols) { unsubscribe(DataTopic.TRADE, Arrays.asList(symbols)); }
    public void unsubscribeQuote(String... symbols) { unsubscribe(DataTopic.QUOTE, Arrays.asList(symbols)); }
    public void unsubscribeForeignRoom(String... symbols) { unsubscribe(DataTopic.ROOM, Arrays.asList(symbols)); }
    public void unsubscribeMarketStatus(String... markets) { unsubscribe(DataTopic.MARKET, Arrays.asList(markets)); }
    public void unsubscribePutThrough(String... symbols) { unsubscribe(DataTopic.PUT, Arrays.asList(symbols)); }
    public void unsubscribeOddLot(String... symbols) { unsubscribe(DataTopic.ODD_LOT, Arrays.asList(symbols)); }
    public void unsubscribeSymbol(String... symbols) { unsubscribeTrade(symbols); unsubscribeQuote(symbols); unsubscribeForeignRoom(symbols); }
    public void unsubscribeBoard(Board... boards) {
        Objects.requireNonNull(boards, "boards");
        unsubscribeSymbol(Arrays.stream(boards).filter(Objects::nonNull).map(Board::value).toArray(String[]::new));
    }
    public void unsubscribeIndex(String... indices) { unsubscribeSymbol(indices); }
    public void unsubscribeOrderStatus() { unsubscribeOrderStatus("*"); }
    public void unsubscribeOrderStatus(String accountNo) { sendTracked(StreamingMethod.UNSUBSCRIBE, StreamingChannel.TRADING, List.of(tradingTopic("order", accountNo))); }
    public void unsubscribePortfolio() { unsubscribePortfolio("*"); }
    public void unsubscribePortfolio(String accountNo) { sendTracked(StreamingMethod.UNSUBSCRIBE, StreamingChannel.TRADING, List.of(tradingTopic("portfolio", accountNo))); }

    public void unsubscribeOhlcv(Timeframe timeframe, String... symbols) {
        Objects.requireNonNull(timeframe, "timeframe");
        List<String> topics = normalized(symbols).stream().map(symbol -> "trade." + symbol + "@" + timeframe.value()).toList();
        sendTracked(StreamingMethod.UNSUBSCRIBE, StreamingChannel.DATA, topics);
    }

    public void ping() { sendUntracked(StreamingMethod.PING_PONG, StreamingChannel.HEARTBEAT, List.of()); }

    public synchronized void startHeartbeat(Duration interval) {
        Objects.requireNonNull(interval, "interval");
        if (interval.isNegative() || interval.toMillis() < 1) throw new IllegalArgumentException("heartbeat interval must be >= 1ms");
        if (closed) throw new IllegalStateException("StreamingService is closed");
        stopHeartbeat();
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "ssi-websocket-heartbeat"));
        heartbeatExecutor.scheduleWithFixedDelay(this::safeHeartbeat, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        if (!config.heartbeatReceiveTimeout().isZero()) {
            long poll = Math.max(1, Math.min(1_000, config.heartbeatReceiveTimeout().toMillis() / 2));
            heartbeatExecutor.scheduleWithFixedDelay(this::checkHeartbeat, poll, poll, TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void stopHeartbeat() {
        ScheduledExecutorService executor = heartbeatExecutor;
        heartbeatExecutor = null;
        if (executor != null) executor.shutdownNow();
    }

    public StreamingService onRaw(Consumer<JsonNode> listener) { dispatcher.onRaw(listener); return this; }
    public StreamingService onTrade(Consumer<TradeMessage> listener) { dispatcher.onTrade(listener); return this; }
    public StreamingService onInterval(Consumer<IntervalMessage> listener) { dispatcher.onInterval(listener); return this; }
    public StreamingService onQuote(Consumer<QuoteMessage> listener) { dispatcher.onQuote(listener); return this; }
    public StreamingService onForeignRoom(Consumer<ForeignRoomMessage> listener) { dispatcher.onForeignRoom(listener); return this; }
    public StreamingService onMarketStatus(Consumer<MarketStatusMessage> listener) { dispatcher.onMarketStatus(listener); return this; }
    public StreamingService onPutThrough(Consumer<PutMessage> listener) { dispatcher.onPutThrough(listener); return this; }
    public StreamingService onOddLot(Consumer<OddLotMessage> listener) { dispatcher.onOddLot(listener); return this; }
    public StreamingService onHeartbeat(Consumer<HeartbeatMessage> listener) { dispatcher.onHeartbeat(listener); return this; }
    public StreamingService onOrderStatus(Consumer<OrderStatusMessage> listener) { dispatcher.onOrderStatus(listener); return this; }
    public StreamingService onPortfolio(Consumer<PortfolioMessage> listener) { dispatcher.onPortfolio(listener); return this; }
    public StreamingService onFcoOrderUpdate(Consumer<FcoOrderUpdateMessage> listener) { dispatcher.onFcoOrderUpdate(listener); return this; }
    public StreamingService onConnected(Runnable listener) { connectedListener = Objects.requireNonNull(listener); return this; }
    public StreamingService onReady(Runnable listener) { readyListener = Objects.requireNonNull(listener); return this; }
    public StreamingService onDisconnected(Consumer<DisconnectEvent> listener) { disconnectedListener = Objects.requireNonNull(listener); return this; }
    public StreamingService onReconnecting(Consumer<ReconnectEvent> listener) { reconnectingListener = Objects.requireNonNull(listener); return this; }
    public StreamingService onAuthenticationRequired(Consumer<AuthenticationException> listener) { authenticationRequiredListener = Objects.requireNonNull(listener); return this; }
    public StreamingService onConnectionError(Consumer<Throwable> listener) { connectionErrorListener = Objects.requireNonNull(listener); return this; }
    public boolean isConnected() { return state == ConnectionState.CONNECTED && webSocket.isConnected(); }
    public boolean isReady() { return ready && isConnected() && heartbeatFresh(); }
    public ConnectionState connectionState() { return state; }
    public Set<StreamingSubscription> activeSubscriptions() { return Set.copyOf(subscriptions); }
    public Instant lastMessageAt() { return lastMessageAt; }
    public Instant lastHeartbeatAt() { return lastHeartbeatAt; }

    public ConnectionDiagnostics diagnostics() {
        synchronized (lifecycleLock) {
            return new ConnectionDiagnostics(state, isReady(), currentAttempt, changedAt, lastMessageAt, lastHeartbeatAt,
                    failureStage, failureReason, errorType, errorStatus, tokenManager.diagnostics());
        }
    }

    /** Resume only after the caller has supplied new authentication; no implicit auth or OTP flow. */
    public void resumeAfterAuthentication() {
        connectInternal(() -> {
            if (!tokenManager.hasValidToken())
                throw new ReconnectAuthenticationException(RecoveryStage.TOKEN_LOOKUP, RecoveryReason.NO_USABLE_TOKEN, 0);
            return tokenManager.token().accessToken();
        });
    }

    public void awaitClose() {
        try { sessionCloseLatch.await(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new WebSocketException("Interrupted while waiting for SSI streaming session", e); }
    }
    public boolean awaitClose(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        try { return sessionCloseLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new WebSocketException("Interrupted while waiting for SSI streaming session", e); }
    }

    public void disconnect() {
        boolean wasActive;
        ScheduledFuture<?> task;
        synchronized (lifecycleLock) {
            wasActive = state != ConnectionState.DISCONNECTED;
            ++lifecycleEpoch;
            manualDisconnect = true;
            reconnectEnabled = false;
            recovering = false;
            task = reconnectFuture;
            reconnectFuture = null;
            ready = false;
            transition(ConnectionState.DISCONNECTED);
            sessionCloseLatch.countDown();
        }
        if (task != null) task.cancel(true);
        stopHeartbeat();
        if (wasActive) safeAccept(disconnectedListener, new DisconnectEvent(WebSocket.NORMAL_CLOSURE, "client closing", true));
        webSocket.disconnect();
    }

    @Override public void close() {
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
        }
        try { disconnect(); }
        finally { reconnectExecutor.shutdownNow(); }
    }

    private void connectInternal(Supplier<String> tokenSupplier) {
        connectLock.lock();
        long epoch;
        try {
            synchronized (lifecycleLock) {
                if (closed) throw new IllegalStateException("StreamingService is closed");
                if (isReady()) return;
                if (reconnectFuture != null) reconnectFuture.cancel(true);
                reconnectFuture = null;
                epoch = ++lifecycleEpoch;
                manualDisconnect = false;
                reconnectEnabled = true;
                recovering = false;
                reauthenticated = false;
                rejectedHandshakes = 0;
                rejectedToken = null;
                ready = false;
                currentAttempt = 0;
                clearFailure();
                sessionCloseLatch = new CountDownLatch(1);
                transition(ConnectionState.CONNECTING);
            }
            try {
                resetTransport();
                String token = tokenSupplier.get();
                if (!active(epoch)) return;
                webSocket.connect(token);
                if (!active(epoch)) { resetTransport(); return; }
                replaySubscriptions();
                completeRecovery(epoch);
            } catch (AuthenticationException failure) {
                terminalAuthentication(epoch, failure);
                throw failure;
            } catch (RuntimeException failure) {
                terminalFailure(epoch, classifyReason(failure, RecoveryStage.HANDSHAKE), failure);
                throw failure;
            }
        } finally { connectLock.unlock(); }
    }

    private void subscribe(DataTopic topic, List<String> symbols) { sendTracked(StreamingMethod.SUBSCRIBE, StreamingChannel.DATA, topics(topic, symbols)); }
    private void unsubscribe(DataTopic topic, List<String> symbols) { sendTracked(StreamingMethod.UNSUBSCRIBE, StreamingChannel.DATA, topics(topic, symbols)); }
    private List<String> topics(DataTopic topic, List<String> values) { return values.stream().filter(Objects::nonNull).map(String::trim).filter(v -> !v.isBlank()).distinct().map(v -> topic.prefix() + "." + v).toList(); }
    private List<String> normalized(String[] values) { return Arrays.stream(values).filter(Objects::nonNull).map(String::trim).filter(v -> !v.isBlank()).distinct().toList(); }
    private String tradingTopic(String prefix, String accountNo) {
        if (accountNo == null || accountNo.isBlank()) throw new IllegalArgumentException("accountNo is required; use * for all accounts");
        return prefix + "." + accountNo.trim();
    }

    private void sendTracked(StreamingMethod method, StreamingChannel channel, List<String> topics) {
        if (topics.isEmpty()) return;
        synchronized (subscriptionLock) {
            webSocket.send(new StreamingRequest(method, channel, topics));
            if (method == StreamingMethod.SUBSCRIBE) topics.forEach(topic -> subscriptions.add(new StreamingSubscription(channel, topic)));
            else if (method == StreamingMethod.UNSUBSCRIBE) topics.forEach(topic -> subscriptions.remove(new StreamingSubscription(channel, topic)));
        }
    }
    private void sendUntracked(StreamingMethod method, StreamingChannel channel, List<String> topics) { webSocket.send(new StreamingRequest(method, channel, topics)); }

    private void replaySubscriptions() {
        synchronized (subscriptionLock) {
            if (subscriptions.isEmpty()) return;
            Map<StreamingChannel, List<String>> grouped = new EnumMap<>(StreamingChannel.class);
            subscriptions.stream().sorted(Comparator.comparing(StreamingSubscription::topic))
                    .forEach(subscription -> grouped.computeIfAbsent(subscription.channel(), ignored -> new ArrayList<>()).add(subscription.topic()));
            grouped.forEach((channel, topics) -> sendUntracked(StreamingMethod.SUBSCRIBE, channel, List.copyOf(topics)));
            log.info("Replayed {} SSI streaming subscription(s)", subscriptions.size());
        }
    }

    private void safeHeartbeat() {
        final long epoch;
        synchronized (lifecycleLock) {
            if (!isConnected()) return;
            epoch = lifecycleEpoch;
        }
        try { ping(); }
        catch (RuntimeException failure) {
            // A transport error callback may already have started recovery before send() throws.
            recover(failure, RecoveryStage.HEARTBEAT, RecoveryReason.HEARTBEAT_SEND_FAILED, epoch);
        }
    }

    private boolean heartbeatFresh() {
        if (heartbeatExecutor == null || config.heartbeatReceiveTimeout().isZero()) return true;
        Instant reference = lastHeartbeatAt == null ? connectedAt : lastHeartbeatAt;
        return reference != null && clock.instant().isBefore(reference.plus(config.heartbeatReceiveTimeout()));
    }

    void checkHeartbeat() {
        final long epoch;
        synchronized (lifecycleLock) {
            if (!isConnected() || heartbeatFresh()) return;
            epoch = lifecycleEpoch;
        }
        recover(new WebSocketException("SSI heartbeat response timed out"), RecoveryStage.HEARTBEAT, RecoveryReason.HEARTBEAT_TIMEOUT, epoch);
    }

    private boolean active(long epoch) {
        synchronized (lifecycleLock) { return lifecycleEpoch == epoch && !closed && !manualDisconnect && reconnectEnabled; }
    }
    private boolean canReconnect() { return !closed && !manualDisconnect && reconnectEnabled && config.autoReconnect(); }

    private void recover(Throwable failure, RecoveryStage stage, RecoveryReason reason) {
        recover(failure, stage, reason, -1);
    }

    private void recover(Throwable failure, RecoveryStage stage, RecoveryReason reason, long expectedEpoch) {
        final long epoch;
        final boolean terminal;
        synchronized (lifecycleLock) {
            if (closed || manualDisconnect || !reconnectEnabled || resettingTransport
                    || expectedEpoch >= 0 && lifecycleEpoch != expectedEpoch) return;
            ready = false;
            recordFailure(stage, reason, failure);
            terminal = !config.autoReconnect();
            if (terminal) {
                epoch = lifecycleEpoch;
                finishTerminal(ConnectionState.FAILED);
            } else {
                if (recovering) { transition(ConnectionState.RECONNECTING); return; }
                recovering = true;
                reauthenticated = false;
                rejectedHandshakes = 0;
                rejectedToken = null;
                epoch = ++lifecycleEpoch;
                transition(ConnectionState.RECONNECTING);
            }
        }
        if (terminal) resetTransportQuietly();
        safeAccept(connectionErrorListener, failure);
        if (!terminal) scheduleAttempt(1, epoch, Duration.ZERO);
    }

    private void scheduleAttempt(int attempt, long epoch, Duration minimumDelay) {
        if (!config.reconnectPolicy().canAttempt(attempt)) {
            terminalFailure(epoch, RecoveryReason.RETRY_EXHAUSTED, new WebSocketException("SSI reconnect budget exhausted"));
            return;
        }
        Duration delay;
        synchronized (lifecycleLock) {
            if (!active(epoch)) return;
            currentAttempt = attempt;
            delay = config.reconnectPolicy().delayForAttempt(attempt);
            if (delay.compareTo(minimumDelay) < 0) delay = minimumDelay;
            transition(ConnectionState.RECONNECTING);
        }
        safeAccept(reconnectingListener, new ReconnectEvent(attempt, config.reconnectPolicy().maxAttempts(), delay));
        synchronized (lifecycleLock) {
            if (!active(epoch)) return;
            reconnectFuture = reconnectExecutor.schedule(() -> reconnectAttempt(attempt, epoch), delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void reconnectAttempt(int attempt, long epoch) {
        boolean locked = false;
        try {
            connectLock.lockInterruptibly();
            locked = true;
            if (active(epoch)) performReconnectAttempt(attempt, epoch);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (active(epoch)) terminalFailure(epoch, RecoveryReason.UNEXPECTED_FAILURE,
                    new WebSocketException("SSI reconnect interrupted", interrupted));
        } finally {
            if (locked) connectLock.unlock();
        }
    }

    private void performReconnectAttempt(int attempt, long epoch) {
        RecoveryStage stage = RecoveryStage.TOKEN_LOOKUP;
        try {
            if (!active(epoch)) return;
            resetTransport();
            final String rejectedAccess;
            final boolean allowAuthentication, allowRefresh;
            synchronized (lifecycleLock) {
                if (!active(epoch)) return;
                rejectedAccess = rejectedToken;
                allowAuthentication = !reauthenticated;
                allowRefresh = rejectedHandshakes < 2;
            }
            TokenManager.ReconnectToken token = tokenManager.resolveReconnectToken(config.reconnectAuthenticationPolicy(),
                    rejectedAccess, allowAuthentication, allowRefresh);
            synchronized (lifecycleLock) {
                if (!active(epoch)) return;
                if (token.authenticated()) reauthenticated = true;
                rejectedToken = null;
            }
            stage = RecoveryStage.HANDSHAKE;
            try { webSocket.connectOnce(token.accessToken()); }
            catch (AuthenticationException rejected) {
                synchronized (lifecycleLock) {
                    if (!active(epoch)) return;
                    if (reauthenticated)
                        throw new ReconnectAuthenticationException(stage, RecoveryReason.HANDSHAKE_AUTH_REJECTED, rejected.statusCode());
                    rejectedToken = token.accessToken();
                    rejectedHandshakes++;
                    recordFailure(stage, RecoveryReason.HANDSHAKE_AUTH_REJECTED, rejected);
                }
                safeAccept(connectionErrorListener, rejected);
                scheduleAttempt(attempt + 1, epoch, Duration.ZERO);
                return;
            }
            if (!active(epoch)) { resetTransport(); return; }
            stage = RecoveryStage.RESTORE_SUBSCRIPTIONS;
            replaySubscriptions();
            if (!webSocket.isConnected()) throw new WebSocketException("SSI connection lost during subscription restore");
            completeRecovery(epoch);
        } catch (AuthenticationException failure) {
            terminalAuthentication(epoch, failure);
        } catch (RuntimeException failure) {
            if (!active(epoch)) return;
            RecoveryStage errorStage = failure instanceof RecoveryException recovery ? recovery.stage() : stage;
            RecoveryReason reason = classifyReason(failure, errorStage);
            synchronized (lifecycleLock) { recordFailure(errorStage, reason, failure); }
            if (!retryable(failure)) {
                terminalFailure(epoch, reason, failure);
                return;
            }
            safeAccept(connectionErrorListener, failure);
            scheduleAttempt(attempt + 1, epoch, retryAfter(failure));
        }
    }

    private void completeRecovery(long epoch) {
        synchronized (lifecycleLock) {
            if (!active(epoch) || !webSocket.isConnected()) return;
            recovering = false;
            reconnectFuture = null;
            ready = true;
            clearFailure();
            transition(ConnectionState.CONNECTED);
        }
        safeRun(readyListener);
    }

    private void terminalAuthentication(long epoch, AuthenticationException failure) {
        synchronized (lifecycleLock) {
            if (!active(epoch)) return;
            RecoveryStage stage = failure instanceof ReconnectAuthenticationException auth ? auth.stage() : RecoveryStage.AUTHENTICATE;
            RecoveryReason reason = failure instanceof ReconnectAuthenticationException auth ? auth.reason() : RecoveryReason.AUTHENTICATION_REJECTED;
            recordFailure(stage, reason, failure);
            finishTerminal(ConnectionState.AUTH_REQUIRED);
        }
        resetTransportQuietly();
        safeAccept(authenticationRequiredListener, failure);
    }

    private void terminalFailure(long epoch, RecoveryReason reason, RuntimeException failure) {
        synchronized (lifecycleLock) {
            if (!active(epoch)) return;
            recordFailure(failureStage == RecoveryStage.NONE ? RecoveryStage.HANDSHAKE : failureStage, reason, failure);
            finishTerminal(ConnectionState.FAILED);
        }
        resetTransportQuietly();
        safeAccept(connectionErrorListener, failure);
    }

    private void finishTerminal(ConnectionState terminal) {
        ready = false;
        recovering = false;
        reconnectEnabled = false;
        reconnectFuture = null;
        transition(terminal);
        sessionCloseLatch.countDown();
        log.warn("SSI recovery stopped: state={}, stage={}, reason={}, status={}, type={}", state, failureStage, failureReason, errorStatus, errorType);
    }

    private void resetTransport() {
        resettingTransport = true;
        try { webSocket.disconnect(); }
        finally { resettingTransport = false; }
    }

    private void resetTransportQuietly() {
        try { resetTransport(); }
        catch (RuntimeException failure) { log.warn("SSI transport cleanup failed: type={}", failure.getClass().getSimpleName()); }
    }

    private RecoveryReason classifyReason(RuntimeException failure, RecoveryStage stage) {
        if (hasCause(failure, InvalidTokenException.class)) return RecoveryReason.INVALID_TOKEN_RESPONSE;
        if (hasCause(failure, RateLimitException.class)) return RecoveryReason.RATE_LIMITED;
        if (stage == RecoveryStage.RESTORE_SUBSCRIPTIONS) return RecoveryReason.SUBSCRIPTION_RESTORE_FAILED;
        return retryable(failure) ? RecoveryReason.TRANSIENT_FAILURE : RecoveryReason.UNEXPECTED_FAILURE;
    }
    private boolean retryable(RuntimeException failure) {
        if (Thread.currentThread().isInterrupted() || hasCause(failure, InvalidTokenException.class)) return false;
        Throwable cause = failure;
        for (int i = 0; cause != null && i < 20; i++, cause = cause.getCause()) {
            if (cause instanceof RateLimitException || cause instanceof IOException || cause instanceof TimeoutException) return true;
            if (cause instanceof SsiApiException api && (api.statusCode() >= 500 || api.statusCode() == 408)) return true;
            if (cause instanceof WebSocketException) return true;
        }
        return false;
    }
    private boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (int i = 0; failure != null && i < 20; i++, failure = failure.getCause()) if (type.isInstance(failure)) return true;
        return false;
    }
    private Duration retryAfter(Throwable failure) {
        for (int i = 0; failure != null && i < 20; i++, failure = failure.getCause()) {
            if (failure instanceof RateLimitException limited && limited.retryAfterSeconds() != null) {
                double seconds = limited.retryAfterSeconds();
                if (Double.isFinite(seconds) && seconds > 0) return Duration.ofMillis((long) Math.min(Long.MAX_VALUE, Math.ceil(seconds * 1_000)));
            }
        }
        return Duration.ZERO;
    }
    private void transition(ConnectionState next) { state = next; changedAt = clock.instant(); }
    private void clearFailure() { failureStage = RecoveryStage.NONE; failureReason = RecoveryReason.NONE; errorType = null; errorStatus = 0; }
    private void recordFailure(RecoveryStage stage, RecoveryReason reason, Throwable failure) {
        failureStage = stage;
        failureReason = reason;
        errorType = failure.getClass().getSimpleName();
        errorStatus = failure instanceof SsiApiException api ? api.statusCode() : 0;
    }
    private void safeRun(Runnable callback) {
        try { callback.run(); } catch (RuntimeException failure) { log.warn("SSI lifecycle callback failed: type={}", failure.getClass().getSimpleName()); }
    }
    private <T> void safeAccept(Consumer<T> callback, T value) {
        try { callback.accept(value); } catch (RuntimeException failure) { log.warn("SSI lifecycle callback failed: type={}", failure.getClass().getSimpleName()); }
    }

    private final class LifecycleListener implements WebSocketLifecycleListener {
        @Override public void onConnected() {
            synchronized (lifecycleLock) {
                if (manualDisconnect || closed) return;
                ready = false;
                connectedAt = clock.instant();
                lastHeartbeatAt = null;
                lastMessageAt = null;
                transition(ConnectionState.CONNECTED);
            }
            safeRun(connectedListener);
        }
        @Override public void onDisconnected(int statusCode, String reason) {
            if (manualDisconnect || closed || resettingTransport) return;
            ready = false;
            safeAccept(disconnectedListener, new DisconnectEvent(statusCode, reason, false));
            if (!config.autoReconnect()) {
                synchronized (lifecycleLock) { reconnectEnabled = false; transition(ConnectionState.DISCONNECTED); sessionCloseLatch.countDown(); }
                return;
            }
            recover(new WebSocketException("SSI socket disconnected: " + statusCode), RecoveryStage.HANDSHAKE, RecoveryReason.TRANSIENT_FAILURE);
        }
        @Override public void onError(Throwable failure) {
            if (manualDisconnect || closed || resettingTransport) return;
            ready = false;
            recover(failure, RecoveryStage.HANDSHAKE, RecoveryReason.TRANSIENT_FAILURE);
        }
    }
}
