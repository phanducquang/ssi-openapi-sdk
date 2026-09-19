package io.github.phanducquang.ssi.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.phanducquang.ssi.auth.model.Token;
import io.github.phanducquang.ssi.config.SsiConfig;
import io.github.phanducquang.ssi.exception.AuthenticationException;
import io.github.phanducquang.ssi.transport.RestClient;
import io.github.phanducquang.ssi.transport.RestTransport;
import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenManagerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void smartOtpRequestPollsUntilTokenIsAvailable() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"transactionId\":\"tx-1\"}");
        transport.enqueue(202, "{\"code\":401114,\"msg\":\"Push-approval is pending\"}");
        transport.enqueue(200, "{\"data\":{\"accessToken\":\"access-1\",\"tokenType\":\"Bearer\",\"expiresAt\":4102444800,\"refreshToken\":\"refresh-1\"}}");
        SsiConfig config = SsiConfig.builder().apiKey("key").apiSecret("secret").smartOtpPollInterval(Duration.ofMillis(1)).smartOtpPollMaxRetries(3).build();
        TokenManager manager = new TokenManager(transport, config, mapper);
        Token token = manager.requestAndAuthenticateSmartOtp();
        assertEquals("access-1", token.accessToken());
        assertEquals("access-1", transport.accessToken);
        assertEquals(List.of(TokenManager.REQUEST_OTP_PATH, TokenManager.ACCESS_TOKEN_PATH, TokenManager.ACCESS_TOKEN_PATH), transport.paths);
    }

    @Test
    void smartOtpTreatsHttp401Code401114AsPending() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"transactionId\":\"tx-401\"}");
        transport.enqueueAuthenticationFailure(
                401,
                "{\"code\":401114,\"msg\":\"Push-approval is pending\"}");
        transport.enqueue(
                200,
                "{\"data\":{\"accessToken\":\"access-after-401\",\"tokenType\":\"Bearer\",\"expiresAt\":4102444800,\"refreshToken\":\"refresh-1\"}}");

        SsiConfig config = SsiConfig.builder()
                .apiKey("key")
                .apiSecret("secret")
                .smartOtpPollInterval(Duration.ofMillis(1))
                .smartOtpPollMaxRetries(3)
                .build();

        TokenManager manager = new TokenManager(transport, config, mapper);
        Token token = manager.requestAndAuthenticateSmartOtp();

        assertEquals("access-after-401", token.accessToken());
        assertEquals(
                List.of(
                        TokenManager.REQUEST_OTP_PATH,
                        TokenManager.ACCESS_TOKEN_PATH,
                        TokenManager.ACCESS_TOKEN_PATH),
                transport.paths);
    }

    @Test
    void ensureAuthenticatedRefreshesExpiredTokenInsteadOfRequestingNewOtp() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"data\":{\"accessToken\":\"expired\",\"expiresAt\":1,\"refreshToken\":\"refresh-1\"}}");
        transport.enqueue(200, "{\"data\":{\"accessToken\":\"refreshed\",\"expiresAt\":4102444800,\"refreshToken\":\"refresh-2\"}}");
        SsiConfig config = SsiConfig.builder().apiKey("key").apiSecret("secret").build();
        TokenManager manager = new TokenManager(transport, config, mapper);
        manager.authenticateWithOtp("123456");
        String accessToken = manager.ensureAuthenticatedWithOtp("654321");
        assertEquals("refreshed", accessToken);
        assertEquals(List.of(TokenManager.ACCESS_TOKEN_PATH, TokenManager.REFRESH_TOKEN_PATH), transport.paths);
        assertTrue(manager.hasValidToken());
    }

    @Test
    void reconnectNeverStartsANewAuthenticationFlowWhenNoRefreshTokenExists() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"data\":{\"accessToken\":\"expired\",\"expiresAt\":1,\"refreshToken\":\"\"}}");
        SsiConfig config = SsiConfig.builder().apiKey("key").apiSecret("secret").build();
        TokenManager manager = new TokenManager(transport, config, mapper);
        manager.authenticateWithOtp("123456");
        assertThrows(AuthenticationException.class, manager::ensureAuthenticatedForReconnect);
        assertEquals(List.of(TokenManager.ACCESS_TOKEN_PATH), transport.paths);
    }

    private final class FakeTransport implements RestTransport {
        private final Queue<Object> responses = new ArrayDeque<>();
        private final List<String> paths = new ArrayList<>();
        private String accessToken;
        void enqueue(int status, String body) throws Exception {
            responses.add(new RestClient.ApiResponse(
                    status,
                    mapper.readTree(body),
                    HttpHeaders.of(Map.of(), (a, b) -> true)));
        }
        void enqueueAuthenticationFailure(int status, String body) throws Exception {
            responses.add(new AuthenticationException(
                    "simulated authentication failure",
                    status,
                    mapper.readTree(body)));
        }
        @Override public RestClient.ApiResponse post(String path, Object body) {
            paths.add(path);
            Object next = responses.remove();
            if (next instanceof RuntimeException failure) throw failure;
            return (RestClient.ApiResponse) next;
        }
        @Override public void setAccessToken(String token) { accessToken = token; }
        @Override public void close() {}
    }
}
