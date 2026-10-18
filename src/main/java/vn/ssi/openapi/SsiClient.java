package vn.ssi.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import vn.ssi.openapi.account.AccountService;
import vn.ssi.openapi.auth.TokenManager;
import vn.ssi.openapi.marketdata.MarketDataService;
import vn.ssi.openapi.portfolio.PortfolioService;
import vn.ssi.openapi.trading.TradingService;
import vn.ssi.openapi.transport.AuthenticatedRestClient;
import vn.ssi.openapi.transport.BusinessRestClient;
import vn.ssi.openapi.config.SsiConfig;
import vn.ssi.openapi.streaming.StreamingMessageDispatcher;
import vn.ssi.openapi.streaming.StreamingService;
import vn.ssi.openapi.transport.RestClient;
import vn.ssi.openapi.transport.websocket.SsiWebSocketClient;

import java.util.Objects;

public final class SsiClient implements AutoCloseable {
    private final RestClient restClient;
    private final BusinessRestClient businessRestClient;
    private final AccountService accountService;
    private final MarketDataService marketDataService;
    private final PortfolioService portfolioService;
    private final TradingService tradingService;
    private final TokenManager tokenManager;
    private final StreamingService streamingService;

    private SsiClient(SsiConfig config) {
        Objects.requireNonNull(config, "config");
        ObjectMapper objectMapper = new ObjectMapper();
        this.restClient = new RestClient(config, objectMapper);
        this.tokenManager = new TokenManager(restClient, config, objectMapper);
        // Existing main authentication transport and lifecycle are deliberately unchanged.
        this.businessRestClient = new BusinessRestClient(config, objectMapper);
        AuthenticatedRestClient authenticated = new AuthenticatedRestClient(businessRestClient, tokenManager);
        this.accountService = new AccountService(authenticated);
        this.marketDataService = new MarketDataService(authenticated);
        this.portfolioService = new PortfolioService(authenticated, config);
        this.tradingService = new TradingService(authenticated, config, objectMapper);
        SsiWebSocketClient webSocketClient = new SsiWebSocketClient(config, objectMapper);
        this.streamingService = new StreamingService(tokenManager, webSocketClient, new StreamingMessageDispatcher(objectMapper), config);
    }

    public static SsiClient create(SsiConfig config) { return new SsiClient(config); }
    public TokenManager auth() { return tokenManager; }
    public StreamingService streaming() { return streamingService; }
    public AccountService account() { return accountService; }
    public MarketDataService marketData() { return marketDataService; }
    public PortfolioService portfolio() { return portfolioService; }
    public TradingService trading() { return tradingService; }
    @Override public void close() { streamingService.close(); restClient.close(); businessRestClient.close(); }
}
