package vn.ssi.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import vn.ssi.openapi.account.AccountService;
import vn.ssi.openapi.auth.TokenManager;
import vn.ssi.openapi.config.SsiConfig;
import vn.ssi.openapi.marketdata.MarketDataService;
import vn.ssi.openapi.portfolio.PortfolioService;
import vn.ssi.openapi.streaming.StreamingMessageDispatcher;
import vn.ssi.openapi.streaming.StreamingService;
import vn.ssi.openapi.trading.TradingService;
import vn.ssi.openapi.transport.AuthenticatedRestClient;
import vn.ssi.openapi.transport.RestClient;
import vn.ssi.openapi.transport.websocket.SsiWebSocketClient;

import java.util.Objects;

public final class SsiClient implements AutoCloseable {
    private final RestClient restClient;
    private final TokenManager tokenManager;
    private final AccountService accountService;
    private final MarketDataService marketDataService;
    private final PortfolioService portfolioService;
    private final TradingService tradingService;
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
        this.portfolioService = new PortfolioService(authenticatedRestClient, config);
        this.tradingService = new TradingService(authenticatedRestClient, config, objectMapper);

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

    public PortfolioService portfolio() {
        return portfolioService;
    }

    public TradingService trading() {
        return tradingService;
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
