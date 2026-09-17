package io.github.phanducquang.ssi.streaming;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.phanducquang.ssi.auth.TokenManager;
import io.github.phanducquang.ssi.streaming.enums.*;
import io.github.phanducquang.ssi.streaming.model.*;
import io.github.phanducquang.ssi.transport.websocket.SsiWebSocketClient;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class StreamingService implements AutoCloseable {
    private final TokenManager tokenManager;
    private final SsiWebSocketClient webSocket;
    private final StreamingMessageDispatcher dispatcher;
    private ScheduledExecutorService heartbeatExecutor;

    public StreamingService(TokenManager tokenManager, SsiWebSocketClient webSocket, StreamingMessageDispatcher dispatcher) {
        this.tokenManager = Objects.requireNonNull(tokenManager);
        this.webSocket = Objects.requireNonNull(webSocket);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.webSocket.onMessage(dispatcher::dispatch);
    }

    public void connect() { webSocket.connect(tokenManager.ensureAuthenticated()); }
    public void connectWithOtp(String otp) { webSocket.connect(tokenManager.ensureAuthenticatedWithOtp(otp)); }
    public void connectWithSmartOtp(String transactionId) { webSocket.connect(tokenManager.ensureAuthenticatedWithSmartOtp(transactionId)); }
    public void connectWithSmartOtpApproval() { webSocket.connect(tokenManager.ensureAuthenticatedWithSmartOtpApproval()); }

    public void subscribeTrade(String... symbols) { subscribe(DataTopic.TRADE, Arrays.asList(symbols)); }
    public void subscribeQuote(String... symbols) { subscribe(DataTopic.QUOTE, Arrays.asList(symbols)); }
    public void subscribeForeignRoom(String... symbols) { subscribe(DataTopic.ROOM, Arrays.asList(symbols)); }
    public void subscribeMarketStatus(String... markets) { subscribe(DataTopic.MARKET, Arrays.asList(markets)); }
    public void subscribePutThrough(String... symbols) { subscribe(DataTopic.PUT, Arrays.asList(symbols)); }
    public void subscribeOddLot(String... symbols) { subscribe(DataTopic.ODD_LOT, Arrays.asList(symbols)); }
    public void subscribeSymbol(String... symbols) { subscribeTrade(symbols); subscribeQuote(symbols); subscribeForeignRoom(symbols); }

    public void subscribeOhlcv(Timeframe timeframe, String... symbols) {
        List<String> topics = normalized(symbols).stream().map(symbol -> "trade." + symbol + "@" + timeframe.value()).toList();
        send(StreamingMethod.SUBSCRIBE, StreamingChannel.DATA, topics);
    }

    public void unsubscribeTrade(String... symbols) { unsubscribe(DataTopic.TRADE, Arrays.asList(symbols)); }
    public void unsubscribeQuote(String... symbols) { unsubscribe(DataTopic.QUOTE, Arrays.asList(symbols)); }
    public void unsubscribeForeignRoom(String... symbols) { unsubscribe(DataTopic.ROOM, Arrays.asList(symbols)); }
    public void unsubscribePutThrough(String... symbols) { unsubscribe(DataTopic.PUT, Arrays.asList(symbols)); }
    public void unsubscribeOddLot(String... symbols) { unsubscribe(DataTopic.ODD_LOT, Arrays.asList(symbols)); }
    public void unsubscribeOhlcv(Timeframe timeframe, String... symbols) { send(StreamingMethod.UNSUBSCRIBE, StreamingChannel.DATA, normalized(symbols).stream().map(symbol -> "trade." + symbol + "@" + timeframe.value()).toList()); }

    public void ping() { send(StreamingMethod.PING_PONG, StreamingChannel.HEARTBEAT, List.of()); }

    public synchronized void startHeartbeat(Duration interval) {
        stopHeartbeat();
        if (interval == null || interval.isZero() || interval.isNegative()) throw new IllegalArgumentException("heartbeat interval must be > 0");
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "ssi-websocket-heartbeat"); t.setDaemon(true); return t; });
        heartbeatExecutor.scheduleWithFixedDelay(this::ping, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
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

    public boolean isConnected() { return webSocket.isConnected(); }
    public void awaitClose() { webSocket.awaitClose(); }
    public boolean awaitClose(Duration timeout) { return webSocket.awaitClose(timeout); }
    public void disconnect() { stopHeartbeat(); webSocket.disconnect(); }
    @Override public void close() { disconnect(); }

    private void subscribe(DataTopic topic, List<String> symbols) { send(StreamingMethod.SUBSCRIBE, StreamingChannel.DATA, topics(topic, symbols)); }
    private void unsubscribe(DataTopic topic, List<String> symbols) { send(StreamingMethod.UNSUBSCRIBE, StreamingChannel.DATA, topics(topic, symbols)); }
    private List<String> topics(DataTopic topic, List<String> values) { return values.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).distinct().map(s -> topic.prefix() + "." + s).toList(); }
    private List<String> normalized(String[] values) { return Arrays.stream(values).filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).distinct().toList(); }
    private void send(StreamingMethod method, StreamingChannel channel, List<String> topics) { webSocket.send(new StreamingRequest(method, channel, topics)); }
}
