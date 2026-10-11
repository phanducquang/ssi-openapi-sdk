package vn.ssi.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import vn.ssi.openapi.auth.TokenManager;
import vn.ssi.openapi.config.SsiConfig;
import vn.ssi.openapi.streaming.StreamingMessageDispatcher;
import vn.ssi.openapi.streaming.StreamingService;
import vn.ssi.openapi.transport.RestClient;
import vn.ssi.openapi.transport.websocket.SsiWebSocketClient;

import java.util.Objects;

public final class SsiClient implements AutoCloseable {
    private final RestClient restClient;
    private final TokenManager tokenManager;
    private final StreamingService streamingService;

    private SsiClient(SsiConfig config) {
        Objects.requireNonNull(config, "config");
        ObjectMapper objectMapper = new ObjectMapper();
        this.restClient = new RestClient(config, objectMapper);
        this.tokenManager = new TokenManager(restClient, config, objectMapper);
        SsiWebSocketClient webSocketClient = new SsiWebSocketClient(config, objectMapper);
        this.streamingService = new StreamingService(tokenManager, webSocketClient, new StreamingMessageDispatcher(objectMapper), config);
    }

    public static SsiClient create(SsiConfig config) { return new SsiClient(config); }
    public TokenManager auth() { return tokenManager; }
    public StreamingService streaming() { return streamingService; }
    @Override public void close() { streamingService.close(); restClient.close(); }
}
