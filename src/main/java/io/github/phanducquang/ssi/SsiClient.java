package io.github.phanducquang.ssi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.phanducquang.ssi.account.AccountService;
import io.github.phanducquang.ssi.auth.TokenManager;
import io.github.phanducquang.ssi.config.SsiConfig;
import io.github.phanducquang.ssi.marketdata.MarketDataService;
import io.github.phanducquang.ssi.streaming.StreamingMessageDispatcher;
import io.github.phanducquang.ssi.streaming.StreamingService;
import io.github.phanducquang.ssi.transport.AuthenticatedRestClient;
import io.github.phanducquang.ssi.transport.RestClient;
import io.github.phanducquang.ssi.transport.websocket.SsiWebSocketClient;

import java.util.Objects;

public final class SsiClient implements AutoCloseable {
    private final RestClient restClient;
    private final TokenManager tokenManager;
    private final AccountService accountService;
    private final MarketDataService marketDataService;
    private final StreamingService streamingService;

    private SsiClient(SsiConfig config) {
        Objects.requireNonNull(config, "config");

        ObjectMapper objectMapper = new ObjectMapper();
        this.restClient = new RestClient(config, objectMapper);
        this.tokenManager = new TokenManager(restClient, config, objectMapper);

        AuthenticatedRestClient authenticatedRestClient =
                new AuthenticatedRestClient(restClient, tokenManager);
        this.accountService = new AccountService(authenticatedRestClient);
        this.marketDataService = new MarketDataService(authenticatedRestClient);

        SsiWebSocketClient webSocketClient = new SsiWebSocketClient(config, objectMapper);
        this.streamingService = new StreamingService(
                tokenManager,
                webSocketClient,
                new StreamingMessageDispatcher(objectMapper),
                config);
    }

    public static SsiClient create(SsiConfig config) {
        return new SsiClient(config);
    }

    public TokenManager auth() {
        return tokenManager;
    }

    public AccountService account() {
        return accountService;
    }

    public MarketDataService marketData() {
        return marketDataService;
    }

    public StreamingService streaming() {
        return streamingService;
    }

    @Override
    public void close() {
        streamingService.close();
        restClient.close();
    }
}
