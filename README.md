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
- Reconnect defaults to refresh-only authentication. Optional API-credential recovery is available for non-interactive market-data accounts; OTP flows are never started implicitly.
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

`autoReconnect` defaults to `true`. Unless a runtime policy is supplied, its reconnect budget/delay derive from `maxRetries` and `retryDelay`, with backoff capped at 60 seconds (or the initial delay if larger). REST and initial-connect retries still use `maxRetries`.

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

## Quote parsing

`QuoteMessage.bidsPresent()` and `asksPresent()` distinguish an omitted side from an explicitly empty side. An omitted field has `present=false` and an empty list; `[]` has `present=true` and an empty list. Explicit `null` and non-array sides are invalid.

Each Quote level must contain exactly `[price, quantity]`. Prices are finite, non-negative `Double` values, including zero. Quantities are non-negative `long` values parsed with `BigDecimal.longValueExact()`: fractional values and overflow are rejected, while integral decimals such as `1.0` are accepted. JSON numbers and valid decimal strings are supported. Levels retain the received Top order, and both side lists are immutable.

Any invalid side, tuple or number rejects the entire Quote. The dispatcher logs the topic and failing field, skips `onQuote`, and continues processing subsequent messages. The raw callback still receives syntactically valid JSON before Quote validation.

The four-argument `QuoteMessage` constructor remains available and infers side presence from non-null lists. `PriceLevel.price()` now returns `Double`, including price levels in `OddLotMessage`; consumers previously using `BigDecimal` for these levels must update their code.

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

The default `REFRESH_ONLY` policy never starts a new authentication or OTP flow. When tokens cannot be used, the state becomes `AUTH_REQUIRED`. Set `API_CREDENTIALS` explicitly for an account allowed to authenticate without OTP:

```java
SsiConfig config = SsiConfig.builder()
        .apiKey(System.getenv("SSI_API_KEY"))
        .apiSecret(System.getenv("SSI_API_SECRET"))
        .reconnectAuthenticationPolicy(ReconnectAuthenticationPolicy.API_CREDENTIALS)
        .reconnectPolicy(new ReconnectPolicy(10, Duration.ofSeconds(2), Duration.ofSeconds(60), 0.2))
        .sendTimeout(Duration.ofSeconds(10))
        .closeTimeout(Duration.ofSeconds(5))
        .build();
```

Both policy types are in `vn.ssi.openapi.config`. `maxAttempts=-1` opts into unlimited runtime retries for transient errors; it does not retry rejected credentials or required user interaction indefinitely. The concrete JDK transport performs one connect per runtime attempt. Legacy custom transports inherit `connectOnce()` as a call to `connect()` and may retain their internal retry behavior until they override it.

Expired/missing tokens and refresh 401/403 can fall back to API-credential authentication under `API_CREDENTIALS`. Refresh/authentication I/O failures, 5xx and rate limits use runtime backoff; valid Retry-After delays are honored. A newly authenticated token rejected by the handshake stops recovery rather than looping authentication. Malformed/expired replacement tokens and unexpected failures produce `FAILED` with diagnostics.

API credentials may only grant market-data permissions. Trading subscriptions/REST calls requiring OTP should retain `REFRESH_ONLY` and resume after the required user authentication:

```java
client.auth().authenticateWithOtp(newOtp);  // obtain a fresh OTP outside the recovery callback
client.streaming().resumeAfterAuthentication();
```

Resume uses an already-valid token, replays the tracked DATA/TRADING subscriptions and retains a running heartbeat scheduler. It never requests or reuses an OTP. Run authentication/resume from application work, not as blocking work inside transport callbacks. `disconnect()`/`close()` cancel pending recovery; a closed StreamingService cannot resume.

`onConnected()` continues to mean transport open. `isReady()`/`onReady()` additionally require completion of subscription replay, and configured heartbeat freshness. Readiness reports successful local sends, not subscription ACKs or receipt of new market data. Track data timestamps/counters independently.

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
client.streaming().lastHeartbeatAt();
client.streaming().isReady();
client.streaming().diagnostics();
```

Possible states: `DISCONNECTED`, `CONNECTING`, `CONNECTED`, `RECONNECTING`, `AUTH_REQUIRED`, `FAILED`. Diagnostics provide attempt, stage/reason, HTTP status and token expiry/presence/validity metadata. They contain no token values, secrets, OTPs or raw authentication response bodies. Runtime authentication callbacks remain `Consumer<AuthenticationException>`; terminal recovery errors carry a `ReconnectAuthenticationException` with stage/reason.

An exhausted finite runtime budget becomes `FAILED` / `RETRY_EXHAUSTED` and unblocks `awaitClose()`. `AUTH_REQUIRED` also unblocks it. Keep the SDK alive to allow explicit resume instead of closing it solely because `awaitClose()` returned. Use application policy to decide how terminal failures are handled.

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

Heartbeat sends only while connected and resumes after recovery. Send failures enter the same reconnect loop. Optional receive-timeout monitoring is disabled by default:

```java
SsiConfig config = SsiConfig.builder()
        .apiKey(System.getenv("SSI_API_KEY"))
        .apiSecret(System.getenv("SSI_API_SECRET"))
        .heartbeatReceiveTimeout(Duration.ofSeconds(75))
        // add any reconnect options
        .build();
SsiClient client = SsiClient.create(config);
client.streaming().connect();
client.streaming().startHeartbeat(Duration.ofSeconds(25));
```

The watchdog is active only while the heartbeat scheduler runs. A new connection receives a full timeout grace period. It monitors recognized heartbeat responses, not missing market ticks outside trading hours. The default validator recognizes `channel=HEARTBEAT`, `method=ping_pong`, `status=ok`, `message=pong`, as documented in the [SSI heartbeat response example](https://developers.ssi.com.vn/docs/api-reference/tradingws-heartbeat). This example is for the trading WebSocket API; confirm the response on your streaming endpoint before enabling the watchdog. Endpoints with a different verified format can supply `heartbeatResponseValidator(Predicate<HeartbeatMessage>)`. Every HEARTBEAT envelope is still delivered to user callbacks; invalid/error heartbeat envelopes do not reset the watchdog.

JDK connect, send and close operations have deadlines. Send/close default to `timeout()` and have separate overrides. Disconnect releases its owned socket after a bounded close send. Session generations prevent old close/error/message events from changing the new session.

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

Unit tests cover the existing API/OTP behaviors plus expired-token credential recovery, rejected/temporary refresh failures, handshake recovery limits, terminal diagnostics, DATA/TRADING replay and readiness, manual resume, concurrent token resolution, finite/unlimited runtime retry, Retry-After, heartbeat validation/timeouts, bounded JDK transport I/O, cancellation and stale session callbacks.

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

    var buyingPower = client.trading()
            .getMaxBuySell(accountNo, "VNM", 61_000);

    // Signed mutation APIs are also available. These examples are intentionally
    // not executed by CI because they can create real trading side effects.
    // client.trading().placeLimitOrder(accountNo, "VNM", OrderSide.BUY, 100, 61_000);

    client.streaming()
            .onOrderStatus(System.out::println)
            .onPortfolio(System.out::println);

    client.streaming().subscribeOrderStatus(accountNo);
    client.streaming().subscribePortfolio(accountNo);
    client.streaming().onFcoOrderUpdate(System.out::println);

    var fcoOrders = client.trading().getFcoByAccountNo(accountNo);
    // FCO mutations are available through client.trading(), but should only
    // be exercised against a controlled trading account.
}
```

Implemented REST groups:
- Account: `GET /api/v3/account/info`.
- Market data: OHLC, index list, index summary, securities info, securities summary and master data.
- Portfolio: equity/derivative balance, order history, equity/derivative positions and PPMMR.
- Core trading: place LO/MTL/ATO/ATC, modify price/quantity, cancel order and max buy/sell.
- TRADING WebSocket: real-time order-status, portfolio and typed FCO event callbacks, wildcard account support, reconnect and automatic subscription replay.
- FCO: list/query, order book, GTD, stop, stop-limit, trailing-stop, trailing-stop-limit, OCO, bull-bear and cancel.
- Signed trading and FCO mutations use RSA PKCS#1 v1.5 SHA-256 and the `X-Signature` header, matching the upstream Python SDK's Base64(XML RSA) private-key format.
- Master data automatically follows `pagesCount` and returns the combined list.
- Business REST calls ensure authentication internally and retry once with a refresh token after an HTTP 401/403.
- The shared REST transport supports GET/POST/PUT/DELETE, query parameters, custom headers and raw JSON bodies for the upcoming signed trading APIs.

Bulk `/api/v3/data/ohlc/download` remains intentionally unimplemented because the upstream Python SDK also leaves it unimplemented.

## Next milestones

1. Live integration validation against SSI with real credentials: auth, market-data REST, DATA/TRADING streaming and reconnect.
2. Controlled trading-account validation for RSA-signed place/modify/cancel and FCO payloads; never run these mutations in public CI.
3. Review any protocol differences discovered by live testing and add recorded fixture tests.
4. Publishing/release automation and versioned Java artifacts.
