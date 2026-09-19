package io.github.phanducquang.ssi.streaming;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.phanducquang.ssi.auth.TokenManager;
import io.github.phanducquang.ssi.config.SsiConfig;
import io.github.phanducquang.ssi.exception.AuthenticationException;
import io.github.phanducquang.ssi.exception.WebSocketException;
import io.github.phanducquang.ssi.streaming.enums.DataTopic;
import io.github.phanducquang.ssi.streaming.enums.StreamingChannel;
import io.github.phanducquang.ssi.streaming.enums.StreamingMethod;
import io.github.phanducquang.ssi.streaming.enums.Timeframe;
import io.github.phanducquang.ssi.streaming.model.*;
import io.github.phanducquang.ssi.transport.websocket.ConnectionState;
import io.github.phanducquang.ssi.transport.websocket.WebSocketLifecycleListener;
import io.github.phanducquang.ssi.transport.websocket.WebSocketTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class StreamingService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(StreamingService.class);
    private final TokenManager tokenManager;
    private final WebSocketTransport webSocket;
    private final StreamingMessageDispatcher dispatcher;
    private final SsiConfig config;
    private final Set<StreamingSubscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean reconnectLoopActive = new AtomicBoolean(false);
    private final ScheduledExecutorService reconnectExecutor;
    private volatile ScheduledExecutorService heartbeatExecutor;
    private volatile ScheduledFuture<?> reconnectFuture;
    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private volatile CountDownLatch sessionCloseLatch = new CountDownLatch(0);
    private volatile Instant lastMessageAt;
    private volatile boolean manualDisconnect = true;
    private volatile boolean reconnectEnabled;
    private volatile boolean closed;
    private volatile Runnable connectedListener = () -> {};
    private volatile Consumer<DisconnectEvent> disconnectedListener = ignored -> {};
    private volatile Consumer<ReconnectEvent> reconnectingListener = ignored -> {};
    private volatile Consumer<AuthenticationException> authenticationRequiredListener = ignored -> {};
    private volatile Consumer<Throwable> connectionErrorListener = ignored -> {};

    public StreamingService(TokenManager tokenManager, WebSocketTransport webSocket, StreamingMessageDispatcher dispatcher, SsiConfig config) {
        this.tokenManager = Objects.requireNonNull(tokenManager);
        this.webSocket = Objects.requireNonNull(webSocket);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.config = Objects.requireNonNull(config);
        this.reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "ssi-websocket-reconnect"); t.setDaemon(true); return t; });
        this.webSocket.onMessage(message -> { lastMessageAt = Instant.now(); dispatcher.dispatch(message); });
        this.webSocket.onLifecycle(new LifecycleListener());
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
        stopHeartbeat();
        if (interval == null || interval.isZero() || interval.isNegative()) throw new IllegalArgumentException("heartbeat interval must be > 0");
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "ssi-websocket-heartbeat"); t.setDaemon(true); return t; });
        heartbeatExecutor.scheduleWithFixedDelay(this::safeHeartbeat, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void stopHeartbeat() { if (heartbeatExecutor != null) { heartbeatExecutor.shutdownNow(); heartbeatExecutor = null; } }
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
    public StreamingService onConnected(Runnable listener) { connectedListener = Objects.requireNonNull(listener); return this; }
    public StreamingService onDisconnected(Consumer<DisconnectEvent> listener) { disconnectedListener = Objects.requireNonNull(listener); return this; }
    public StreamingService onReconnecting(Consumer<ReconnectEvent> listener) { reconnectingListener = Objects.requireNonNull(listener); return this; }
    public StreamingService onAuthenticationRequired(Consumer<AuthenticationException> listener) { authenticationRequiredListener = Objects.requireNonNull(listener); return this; }
    public StreamingService onConnectionError(Consumer<Throwable> listener) { connectionErrorListener = Objects.requireNonNull(listener); return this; }
    public boolean isConnected() { return state == ConnectionState.CONNECTED && webSocket.isConnected(); }
    public ConnectionState connectionState() { return state; }
    public Set<StreamingSubscription> activeSubscriptions() { return Set.copyOf(subscriptions); }
    public Instant lastMessageAt() { return lastMessageAt; }

    public void awaitClose() {
        try { sessionCloseLatch.await(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new WebSocketException("Interrupted while waiting for SSI streaming session to close", e); }
    }

    public boolean awaitClose(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        try { return sessionCloseLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new WebSocketException("Interrupted while waiting for SSI streaming session to close", e); }
    }

    public synchronized void disconnect() {
        boolean wasActive = state != ConnectionState.DISCONNECTED;
        manualDisconnect = true;
        reconnectEnabled = false;
        cancelReconnect();
        stopHeartbeat();
        state = ConnectionState.DISCONNECTED;
        sessionCloseLatch.countDown();
        if (wasActive) safeAccept(disconnectedListener, new DisconnectEvent(WebSocket.NORMAL_CLOSURE, "client closing", true), "manual disconnect callback");
        webSocket.disconnect();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        disconnect();
        reconnectExecutor.shutdownNow();
    }

    private synchronized void connectInternal(Supplier<String> accessTokenSupplier) {
        if (closed) throw new IllegalStateException("StreamingService is closed");
        if (isConnected()) return;
        manualDisconnect = false;
        reconnectEnabled = true;
        cancelReconnect();
        sessionCloseLatch = new CountDownLatch(1);
        state = ConnectionState.CONNECTING;
        try { webSocket.connect(accessTokenSupplier.get()); }
        catch (AuthenticationException e) {
            reconnectEnabled = false;
            state = ConnectionState.AUTH_REQUIRED;
            sessionCloseLatch.countDown();
            safeAccept(authenticationRequiredListener, e, "authentication-required callback");
            throw e;
        } catch (RuntimeException e) {
            reconnectEnabled = false;
            state = ConnectionState.FAILED;
            sessionCloseLatch.countDown();
            safeAccept(connectionErrorListener, e, "connection-error callback");
            throw e;
        }
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
        webSocket.send(new StreamingRequest(method, channel, topics));
        if (method == StreamingMethod.SUBSCRIBE) topics.forEach(topic -> subscriptions.add(new StreamingSubscription(channel, topic)));
        else if (method == StreamingMethod.UNSUBSCRIBE) topics.forEach(topic -> subscriptions.remove(new StreamingSubscription(channel, topic)));
    }

    private void sendUntracked(StreamingMethod method, StreamingChannel channel, List<String> topics) { webSocket.send(new StreamingRequest(method, channel, topics)); }

    private void replaySubscriptions() {
        if (subscriptions.isEmpty()) return;
        Map<StreamingChannel, List<String>> grouped = new EnumMap<>(StreamingChannel.class);
        subscriptions.stream().sorted(Comparator.comparing(StreamingSubscription::topic)).forEach(subscription -> grouped.computeIfAbsent(subscription.channel(), ignored -> new ArrayList<>()).add(subscription.topic()));
        grouped.forEach((channel, topics) -> sendUntracked(StreamingMethod.SUBSCRIBE, channel, List.copyOf(topics)));
        log.info("Replayed {} SSI streaming subscription(s) after reconnect", subscriptions.size());
    }

    private void safeHeartbeat() {
        if (!isConnected()) return;
        try { ping(); } catch (RuntimeException e) { log.warn("SSI heartbeat send failed; reconnect lifecycle will handle connection loss", e); }
    }

    private void scheduleReconnect() {
        if (!canReconnect()) return;
        if (!reconnectLoopActive.compareAndSet(false, true)) { state = ConnectionState.RECONNECTING; return; }
        scheduleReconnectAttempt(1);
    }

    private void scheduleReconnectAttempt(int attempt) {
        if (!canReconnect()) { reconnectLoopActive.set(false); return; }
        if (attempt > config.maxRetries()) {
            reconnectLoopActive.set(false);
            state = ConnectionState.FAILED;
            sessionCloseLatch.countDown();
            safeAccept(connectionErrorListener, new WebSocketException("SSI WebSocket reconnect failed after " + config.maxRetries() + " attempts"), "connection-error callback");
            return;
        }
        Duration delay = reconnectDelay(attempt);
        state = ConnectionState.RECONNECTING;
        safeAccept(reconnectingListener, new ReconnectEvent(attempt, config.maxRetries(), delay), "reconnecting callback");
        reconnectFuture = reconnectExecutor.schedule(() -> reconnectAttempt(attempt), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void reconnectAttempt(int attempt) {
        if (!canReconnect()) { reconnectLoopActive.set(false); return; }
        final String accessToken;
        try { accessToken = tokenManager.ensureAuthenticatedForReconnect(); }
        catch (AuthenticationException e) { authenticationRequired(e); return; }
        try { webSocket.connect(accessToken); }
        catch (AuthenticationException handshakeFailure) {
            try { tokenManager.refreshForReconnect(); }
            catch (AuthenticationException refreshFailure) { authenticationRequired(refreshFailure); return; }
            safeAccept(connectionErrorListener, handshakeFailure, "connection-error callback");
            scheduleReconnectAttempt(attempt + 1);
            return;
        } catch (RuntimeException e) {
            safeAccept(connectionErrorListener, e, "connection-error callback");
            scheduleReconnectAttempt(attempt + 1);
            return;
        }
        try {
            replaySubscriptions();
            reconnectLoopActive.set(false);
            reconnectFuture = null;
        } catch (RuntimeException e) {
            safeAccept(connectionErrorListener, e, "connection-error callback");
            webSocket.disconnect();
            scheduleReconnectAttempt(attempt + 1);
        }
    }

    private void authenticationRequired(AuthenticationException exception) {
        reconnectLoopActive.set(false);
        reconnectFuture = null;
        reconnectEnabled = false;
        state = ConnectionState.AUTH_REQUIRED;
        sessionCloseLatch.countDown();
        safeAccept(authenticationRequiredListener, exception, "authentication-required callback");
    }

    private boolean canReconnect() { return !closed && !manualDisconnect && reconnectEnabled && config.autoReconnect(); }

    private Duration reconnectDelay(int attempt) {
        long multiplier = 1L << Math.min(Math.max(0, attempt - 1), 20);
        long baseMillis = config.retryDelay().toMillis();
        try { return Duration.ofMillis(Math.multiplyExact(baseMillis, multiplier)); }
        catch (ArithmeticException overflow) { return Duration.ofMillis(Long.MAX_VALUE); }
    }

    private synchronized void cancelReconnect() {
        ScheduledFuture<?> future = reconnectFuture;
        reconnectFuture = null;
        if (future != null) future.cancel(true);
        reconnectLoopActive.set(false);
    }

    private void handleConnected() { state = ConnectionState.CONNECTED; safeRun(connectedListener, "connected callback"); }

    private void handleDisconnected(int statusCode, String reason) {
        if (manualDisconnect || closed) { state = ConnectionState.DISCONNECTED; return; }
        state = ConnectionState.DISCONNECTED;
        safeAccept(disconnectedListener, new DisconnectEvent(statusCode, reason, false), "disconnected callback");
        scheduleReconnect();
    }

    private void handleError(Throwable error) {
        if (manualDisconnect || closed) return;
        state = ConnectionState.FAILED;
        safeAccept(connectionErrorListener, error, "connection-error callback");
        scheduleReconnect();
    }

    private void safeRun(Runnable callback, String name) { try { callback.run(); } catch (RuntimeException e) { log.error("SSI streaming {} failed", name, e); } }
    private <T> void safeAccept(Consumer<T> callback, T value, String name) { try { callback.accept(value); } catch (RuntimeException e) { log.error("SSI streaming {} failed", name, e); } }

    private final class LifecycleListener implements WebSocketLifecycleListener {
        @Override public void onConnected() { handleConnected(); }
        @Override public void onDisconnected(int statusCode, String reason) { handleDisconnected(statusCode, reason); }
        @Override public void onError(Throwable error) { handleError(error); }
    }
}
