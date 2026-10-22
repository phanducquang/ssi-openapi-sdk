package vn.ssi.openapi.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.ssi.openapi.auth.TokenManager;
import vn.ssi.openapi.config.SsiConfig;
import vn.ssi.openapi.exception.AuthenticationException;
import vn.ssi.openapi.exception.ReconnectAuthenticationException;
import vn.ssi.openapi.streaming.model.RecoveryReason;
import vn.ssi.openapi.streaming.model.RecoveryStage;
import vn.ssi.openapi.exception.SsiApiException;

import java.net.http.HttpHeaders;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthenticatedRestClientTest {
    private static final String PATH = "/api/v3/trading/order";
    private static final String BODY = "{\"clientRequestId\":\"request-1\",\"price\":\"61000\"}";
    private static final Map<String, String> HEADERS = Map.of("X-Signature", "test-signature");
    private static final Map<String, Object> QUERY = Map.of("accountNo", "test-account");

    private final ObjectMapper mapper = new ObjectMapper();
    private final RecordingTransport transport = new RecordingTransport();
    private final TokenManager tokens = new TokenManager(
            transport, SsiConfig.builder().apiKey("test-key").apiSecret("test-secret").build(), mapper);
    private final AuthenticatedRestClient client = new AuthenticatedRestClient(transport, tokens);

    @Test
    void authenticatesBeforeSendingBusinessRequest() throws Exception {
        transport.responses.add(token("access-1", "refresh-1"));
        RestClient.ApiResponse expected = response("{\"data\":{\"orderId\":\"order-1\"}}");
        transport.responses.add(expected);

        assertSame(expected, sendOrder());
        assertEquals(List.of(TokenManager.ACCESS_TOKEN_PATH, PATH), transport.paths());
        assertOrder(transport.calls.get(1), "access-1");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void refreshesAndRetriesSignedPayloadWithoutChangingIt(int status) throws Exception {
        transport.responses.add(token("access-1", "refresh-1"));
        transport.responses.add(authenticationFailure(status));
        transport.responses.add(token("access-2", "refresh-2"));
        RestClient.ApiResponse expected = response("{\"data\":{\"orderId\":\"order-1\"}}");
        transport.responses.add(expected);

        assertSame(expected, sendOrder());
        assertEquals(List.of(TokenManager.ACCESS_TOKEN_PATH, PATH, TokenManager.REFRESH_TOKEN_PATH, PATH),
                transport.paths());
        assertOrder(transport.calls.get(1), "access-1");
        assertOrder(transport.calls.get(3), "access-2");
    }

    @Test
    void stopsAfterSecondAuthenticationFailure() throws Exception {
        transport.responses.add(token("access-1", "refresh-1"));
        transport.responses.add(authenticationFailure(401));
        transport.responses.add(token("access-2", "refresh-2"));
        AuthenticationException secondFailure = authenticationFailure(403);
        transport.responses.add(secondFailure);

        assertSame(secondFailure, assertThrows(AuthenticationException.class, this::sendOrder));
        assertEquals(List.of(TokenManager.ACCESS_TOKEN_PATH, PATH, TokenManager.REFRESH_TOKEN_PATH, PATH),
                transport.paths());
    }

    @Test
    void preservesOriginalFailureWhenRefreshFails() throws Exception {
        transport.responses.add(token("access-1", "refresh-1"));
        AuthenticationException firstFailure = authenticationFailure(401);
        AuthenticationException refreshFailure = authenticationFailure(403);
        transport.responses.add(firstFailure);
        transport.responses.add(refreshFailure);

        assertSame(firstFailure, assertThrows(AuthenticationException.class, this::sendOrder));
        assertEquals(1, firstFailure.getSuppressed().length);
        ReconnectAuthenticationException classified = assertInstanceOf(
                ReconnectAuthenticationException.class, firstFailure.getSuppressed()[0]);
        assertEquals(RecoveryReason.REFRESH_REJECTED, classified.reason());
        assertEquals(RecoveryStage.REFRESH, classified.stage());
        assertEquals(refreshFailure.statusCode(), classified.statusCode());
        assertNull(classified.responseBody());
        assertEquals(List.of(TokenManager.ACCESS_TOKEN_PATH, PATH, TokenManager.REFRESH_TOKEN_PATH),
                transport.paths());
    }

    @Test
    void doesNotRefreshOrRetryOnOtherApiFailures() throws Exception {
        transport.responses.add(token("access-1", "refresh-1"));
        SsiApiException failure = new SsiApiException("simulated API failure", 500, mapper.createObjectNode());
        transport.responses.add(failure);

        assertSame(failure, assertThrows(SsiApiException.class, this::sendOrder));
        assertEquals(List.of(TokenManager.ACCESS_TOKEN_PATH, PATH), transport.paths());
    }

    @Test
    void doesNotSendBusinessRequestWhenInitialAuthenticationFails() {
        AuthenticationException failure = authenticationFailure(401);
        transport.responses.add(failure);

        assertSame(failure, assertThrows(AuthenticationException.class, this::sendOrder));
        assertEquals(List.of(TokenManager.ACCESS_TOKEN_PATH), transport.paths());
    }

    private RestClient.ApiResponse sendOrder() {
        return client.request("POST", PATH, QUERY, BODY, HEADERS);
    }

    private void assertOrder(Call call, String accessToken) {
        assertEquals("POST", call.method());
        assertEquals(PATH, call.path());
        assertSame(QUERY, call.query());
        assertSame(BODY, call.body());
        assertSame(HEADERS, call.headers());
        assertEquals(accessToken, call.accessToken());
    }

    private RestClient.ApiResponse token(String accessToken, String refreshToken) {
        JsonNode body = mapper.createObjectNode().set("data", mapper.createObjectNode()
                .put("accessToken", accessToken)
                .put("expiresAt", 4102444800L)
                .put("refreshToken", refreshToken));
        return new RestClient.ApiResponse(200, body, HttpHeaders.of(Map.of(), (name, value) -> true));
    }

    private RestClient.ApiResponse response(String body) throws Exception {
        return new RestClient.ApiResponse(200, mapper.readTree(body),
                HttpHeaders.of(Map.of(), (name, value) -> true));
    }

    private AuthenticationException authenticationFailure(int status) {
        return new AuthenticationException("simulated authentication failure", status, mapper.createObjectNode());
    }

    private record Call(String method, String path, Map<String, ?> query, Object body,
                        Map<String, String> headers, String accessToken) {
    }

    private static final class RecordingTransport implements RestTransport {
        private final Queue<Object> responses = new ArrayDeque<>();
        private final List<Call> calls = new ArrayList<>();
        private String accessToken;

        private List<String> paths() {
            return calls.stream().map(Call::path).toList();
        }

        @Override
        public RestClient.ApiResponse post(String path, Object body) {
            return request("POST", path, Map.of(), body, Map.of());
        }

        @Override
        public RestClient.ApiResponse request(String method, String path, Map<String, ?> query,
                                              Object body, Map<String, String> headers) {
            calls.add(new Call(method, path, query, body, headers, accessToken));
            Object response = responses.remove();
            if (response instanceof RuntimeException failure) {
                throw failure;
            }
            return (RestClient.ApiResponse) response;
        }

        @Override
        public void setAccessToken(String token) {
            accessToken = token;
        }

        @Override
        public void close() {
        }
    }
}
