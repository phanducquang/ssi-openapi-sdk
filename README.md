# SSI OpenAPI SDK for Java

Unofficial Java 17 port of the public SSI Python SDK, focused first on authentication and real-time WebSocket market data.

The SDK owns the access-token lifecycle. Application code supplies SSI credentials and, when required, an OTP or approves Smart OTP; it does **not** fetch an access token and inject it into the WebSocket client manually.

## Implemented

- Java 17, no Spring dependency.
- Auth: `/api/v3/auth/requestOtp`, `/api/v3/auth/token`, `/api/v3/auth/refresh`.
- Normal OTP and Smart OTP request + automatic polling (`202` / code `401114`).
- Refresh-token reuse when access token expires.
- Authenticated WebSocket to `wss://stream.ssi.com.vn/ws/v3`.
- Trade, quote, foreign room, market status, put-through, odd lot, OHLCV subscriptions.
- Heartbeat ping and typed callbacks.
- Raw JSON callback for acknowledgement/unmapped messages.

## Configuration

```java
SsiConfig config = SsiConfig.builder()
        .clientId(System.getenv("SSI_CLIENT_ID"))
        .apiKey(System.getenv("SSI_API_KEY"))
        .apiSecret(System.getenv("SSI_API_SECRET"))
        .privateKey(System.getenv("SSI_PRIVATE_KEY"))
        .build();

SsiClient client = SsiClient.create(config);
```

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

    // requestOtp -> transactionId -> poll auth/token until approved -> WebSocket
    client.streaming().connectWithSmartOtpApproval();

    client.streaming().subscribeSymbol("VNM", "SSI");
    client.streaming().awaitClose();
}
```

The user still approves the push notification on the SSI device. Polling is configurable with `smartOtpPollInterval(...)` and `smartOtpPollMaxRetries(...)`.

If the application already has a Smart OTP `transactionId`, call `connectWithSmartOtp(transactionId)`. The application still never passes an access token.

## Credential-only auth

For SSI scopes/accounts that allow API key + secret without OTP:

```java
client.streaming().connect();
```

## OHLCV

```java
client.streaming().onInterval(System.out::println);
client.streaming().subscribeOhlcv(Timeframe.MINUTE_1, "VNM", "FPT");
```

Intervals mirror the Python SDK: `1m`, `3m`, `5m`, `15m`, `1h`, `1d`, `1w`, `1M`.

## Architecture

```text
SsiClient
  |-- TokenManager
  |     |-- requestOtp
  |     |-- authenticate / Smart OTP polling
  |     `-- refresh
  `-- StreamingService
        |-- SsiWebSocketClient
        `-- StreamingMessageDispatcher
```

## Next milestones

1. Automatic WebSocket reconnect + subscription replay.
2. Trading-channel stream: order status, portfolio, FCO events.
3. Market Data REST APIs.
4. Account/portfolio/trading/FCO REST APIs.
5. Publishing/release automation.
