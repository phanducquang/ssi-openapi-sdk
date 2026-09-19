# SSI OpenAPI SDK for Java

Unofficial Java 17 port of the public SSI Python SDK, focused first on authentication and real-time WebSocket market data.

The SDK owns the access-token lifecycle. Application code supplies SSI credentials and, when required, an OTP or approves Smart OTP; it does **not** fetch an access token and inject it into the WebSocket client manually.

## Implemented

- Java 17, no Spring dependency.
- Auth: `/api/v3/auth/requestOtp`, `/api/v3/auth/token`, `/api/v3/auth/refresh`.
- Normal OTP and Smart OTP request + automatic polling (`202` / code `401114`).
- Refresh-token reuse when access token expires.
- Authenticated WebSocket to `wss://stream.ssi.com.vn/ws/v3`.
- Trade, quote, foreign room, market status, put-through, odd lot and OHLCV subscriptions.
- Subscription registry + automatic replay after reconnect.
- Automatic reconnect with exponential backoff.
- Reconnect never starts a new OTP flow automatically. If neither the access token nor refresh token can be used, state becomes `AUTH_REQUIRED`.
- Manual disconnect never triggers reconnect.
- Connection lifecycle callbacks and connection diagnostics.
- Heartbeat survives transient disconnects and resumes after reconnect.
- Typed market-data callbacks plus raw JSON callback.

## Configuration

```java
SsiConfig config = SsiConfig.builder()
        .clientId(System.getenv("SSI_CLIENT_ID"))
        .apiKey(System.getenv("SSI_API_KEY"))
        .apiSecret(System.getenv("SSI_API_SECRET"))
        .privateKey(System.getenv("SSI_PRIVATE_KEY"))
        .autoReconnect(true)
        .build();

SsiClient client = SsiClient.create(config);
```

`autoReconnect` defaults to `true`. Reconnect uses `maxRetries` and `retryDelay` with exponential backoff.

Do not commit credentials, OTPs, refresh tokens or access tokens.

## Normal OTP streaming

```java
try (SsiClient client = SsiClient.create(config)) {
    client.auth().requestOtp();
    String otp = System.getenv("SSI_OTP");

    client.streaming()
            .onTrade(trade -> System.out.printf("%s price=%s qty=%d%n", trade.symbol(), trade.price(), trade.quantity()))
            .onQuote(System.out::println);

    client.streaming().connectWithOtp(otp);
    client.streaming().subscribeTrade("VNM", "SSI", "FPT");
    client.streaming().subscribeQuote("VNM");
    client.streaming().awaitClose();
}
```

## Smart OTP: SDK handles request + poll + token

```java
try (SsiClient client = SsiClient.create(config)) {
    client.streaming().onTrade(System.out::println);
    client.streaming().connectWithSmartOtpApproval();
    client.streaming().subscribeSymbol("VNM", "SSI");
    client.streaming().awaitClose();
}
```

The user still approves the push notification on the SSI device. Polling is configurable with `smartOtpPollInterval(...)` and `smartOtpPollMaxRetries(...)`.

## Reconnect and subscription replay

Every successful subscribe is stored in the SDK's in-memory subscription registry. Unexpected disconnects reuse a valid access token, refresh an expired token when possible, reconnect with exponential backoff, then replay active subscriptions. Calling `disconnect()` is intentional and never triggers reconnect.

The reconnect path intentionally does **not** request a new OTP or Smart OTP. If neither access token nor refresh token can be used, the state becomes `AUTH_REQUIRED` and application interaction is required.

## Connection observability

```java
client.streaming()
        .onConnected(() -> System.out.println("SSI stream connected"))
        .onDisconnected(event -> System.out.println("SSI stream disconnected: " + event))
        .onReconnecting(event -> System.out.printf("Reconnect %d/%d after %s%n", event.attempt(), event.maxAttempts(), event.delay()))
        .onAuthenticationRequired(error -> System.out.println("SSI authentication required: " + error.getMessage()))
        .onConnectionError(Throwable::printStackTrace);
```

Runtime diagnostics:

```java
client.streaming().connectionState();
client.streaming().isConnected();
client.streaming().activeSubscriptions();
client.streaming().lastMessageAt();
```

Possible states: `DISCONNECTED`, `CONNECTING`, `CONNECTED`, `RECONNECTING`, `AUTH_REQUIRED`, `FAILED`.

## OHLCV

```java
client.streaming().onInterval(System.out::println);
client.streaming().subscribeOhlcv(Timeframe.MINUTE_1, "VNM", "FPT");
```

Intervals mirror the Python SDK: `1m`, `3m`, `5m`, `15m`, `1h`, `1d`, `1w`, `1M`.

## Heartbeat

```java
client.streaming().startHeartbeat(Duration.ofSeconds(30));
```

Heartbeat sends only while the socket is connected. A transient disconnect does not destroy the scheduler, so heartbeat resumes after a successful reconnect.

## Architecture

```text
SsiClient
  |-- TokenManager
  |     |-- requestOtp / authenticate / Smart OTP polling
  |     |-- refresh
  |     `-- reconnect-safe token resolution
  `-- StreamingService
        |-- subscription registry
        |-- reconnect orchestration
        |-- lifecycle/observability
        |-- StreamingMessageDispatcher
        `-- WebSocketTransport
              `-- SsiWebSocketClient (JDK WebSocket)
```

Transport owns the WebSocket connection. `StreamingService` owns reconnect orchestration because it has access to both token lifecycle and active subscriptions.

## Current validation status

Unit tests cover Smart OTP polling, refresh-token reuse, reconnect without implicit OTP, unexpected disconnect -> reconnect -> subscription replay, manual disconnect -> no reconnect, subscribe/unsubscribe registry behavior, request serialization and typed trade/quote dispatch.

Live SSI authentication and WebSocket integration still requires real SSI credentials and OTP/Smart OTP approval and is not performed by automated tests.

## REST APIs

The SDK now exposes authenticated read-only REST services in addition to WebSocket streaming.

```java
try (SsiClient client = SsiClient.create(config)) {
    client.auth().authenticateWithOtp(otp);

    var accounts = client.account().getAccountInfo();

    var candles = client.marketData()
            .getOhlc1DayHistorical("VNM", "2026/09/01", "2026/09/17");

    var vn30 = client.marketData().getIndexSummary("VN30");
    var hoseSymbols = client.marketData().getSecuritiesInfoByBoard(Board.HOSE);
    var masterData = client.marketData().getMasterData();

    var equityBalance = client.portfolio().getEquityBalance(accountNo);
    var positions = client.portfolio().getEquityPositions(accountNo);
    var todayOrders = client.portfolio().getTodayOrders(accountNo);
    var ppmmr = client.portfolio().getEquityPpmmr(accountNo);
}
```

Implemented REST groups:
- Account: `GET /api/v3/account/info`.
- Market data: OHLC, index list, index summary, securities info, securities summary and master data.
- Portfolio: equity/derivative balance, order history, equity/derivative positions and PPMMR.
- Master data automatically follows `pagesCount` and returns the combined list.
- Business REST calls ensure authentication internally and retry once with a refresh token after an HTTP 401/403.
- The shared REST transport supports GET/POST/PUT/DELETE, query parameters, custom headers and raw JSON bodies for the upcoming signed trading APIs.

Bulk `/api/v3/data/ohlc/download` remains intentionally unimplemented because the upstream Python SDK also leaves it unimplemented.

## Next milestones

1. Core Trading REST APIs: place, modify, cancel and max buy/sell.
2. Shared RSA SHA-256 request signing and request-id generation for signed trading operations.
3. TRADING-channel stream: order status and portfolio events, reusing the newly ported order/portfolio models.
4. FCO REST APIs and FCO streaming events.
5. Live integration validation against SSI with real credentials.
6. Publishing/release automation.
