package io.github.phanducquang.ssi.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.phanducquang.ssi.auth.model.OtpRequest;
import io.github.phanducquang.ssi.auth.model.OtpResponse;
import io.github.phanducquang.ssi.auth.model.RefreshTokenRequest;
import io.github.phanducquang.ssi.auth.model.Token;
import io.github.phanducquang.ssi.auth.model.TokenRequest;
import io.github.phanducquang.ssi.config.SsiConfig;
import io.github.phanducquang.ssi.exception.AuthenticationException;
import io.github.phanducquang.ssi.exception.SsiApiException;
import io.github.phanducquang.ssi.transport.RestClient;
import io.github.phanducquang.ssi.transport.RestTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;

public final class TokenManager {
    public static final String ACCESS_TOKEN_PATH = "/api/v3/auth/token";
    public static final String REFRESH_TOKEN_PATH = "/api/v3/auth/refresh";
    public static final String REQUEST_OTP_PATH = "/api/v3/auth/requestOtp";
    private static final int SMART_OTP_PENDING_STATUS = 202;
    private static final int SMART_OTP_PENDING_CODE = 401114;
    private static final Logger log = LoggerFactory.getLogger(TokenManager.class);

    private final RestTransport restClient;
    private final SsiConfig config;
    private final ObjectMapper objectMapper;
    private volatile Token token;

    public TokenManager(RestTransport restClient, SsiConfig config, ObjectMapper objectMapper) {
        this.restClient = Objects.requireNonNull(restClient);
        this.config = Objects.requireNonNull(config);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public OtpResponse requestOtp() {
        validateCredentials();
        RestClient.ApiResponse response = restClient.post(REQUEST_OTP_PATH, new OtpRequest(config.apiKey(), config.apiSecret()));
        JsonNode body = response.body();
        String transactionId = text(body, "transactionId");
        if (transactionId.isBlank() && body.has("data")) transactionId = text(body.path("data"), "transactionId");
        return new OtpResponse(transactionId, body);
    }

    public synchronized Token authenticate() { return authenticateInternal(new TokenRequest(config.apiKey(), config.apiSecret(), null, null)); }

    public synchronized Token authenticateWithOtp(String otp) {
        if (otp == null || otp.isBlank()) throw new AuthenticationException("otp is required");
        return authenticateInternal(new TokenRequest(config.apiKey(), config.apiSecret(), otp, null));
    }

    public synchronized Token authenticateWithTransactionId(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) throw new AuthenticationException("transactionId is required");
        return authenticateInternal(new TokenRequest(config.apiKey(), config.apiSecret(), null, transactionId));
    }

    public Token authenticateSmartOtp(String transactionId) {
        for (int attempt = 1; attempt <= config.smartOtpPollMaxRetries(); attempt++) {
            try { return authenticateWithTransactionId(transactionId); }
            catch (SmartOtpPendingException pending) {
                if (attempt >= config.smartOtpPollMaxRetries()) throw new AuthenticationException("Smart OTP approval was not confirmed after " + attempt + " attempts", pending.statusCode(), pending.responseBody());
                log.info("Smart OTP approval pending ({}/{}), retrying in {} ms", attempt, config.smartOtpPollMaxRetries(), config.smartOtpPollInterval().toMillis());
                sleep(config.smartOtpPollInterval().toMillis());
            }
        }
        throw new AuthenticationException("Smart OTP authentication failed");
    }

    public Token requestAndAuthenticateSmartOtp() {
        OtpResponse otpResponse = requestOtp();
        if (otpResponse.transactionId() == null || otpResponse.transactionId().isBlank()) throw new AuthenticationException("SSI requestOtp response did not contain transactionId");
        return authenticateSmartOtp(otpResponse.transactionId());
    }

    public synchronized Token refresh() {
        Token current = token;
        long now = Instant.now().getEpochSecond();
        if (current == null || !current.hasValidRefreshToken(now)) throw new AuthenticationException("No valid refresh token available; authenticate again");
        RestClient.ApiResponse response = restClient.post(REFRESH_TOKEN_PATH, new RefreshTokenRequest(current.refreshToken()));
        Token refreshed = parseToken(response.body(), "refreshing token");
        setToken(refreshed);
        return refreshed;
    }

    public String ensureAuthenticated() {
        if (hasValidToken()) return token.accessToken();
        if (hasValidRefreshToken()) return refresh().accessToken();
        return authenticate().accessToken();
    }

    public String ensureAuthenticatedWithOtp(String otp) {
        if (hasValidToken()) return token.accessToken();
        if (hasValidRefreshToken()) return refresh().accessToken();
        return authenticateWithOtp(otp).accessToken();
    }

    public String ensureAuthenticatedWithSmartOtp(String transactionId) {
        if (hasValidToken()) return token.accessToken();
        if (hasValidRefreshToken()) return refresh().accessToken();
        return authenticateSmartOtp(transactionId).accessToken();
    }

    public String ensureAuthenticatedWithSmartOtpApproval() {
        if (hasValidToken()) return token.accessToken();
        if (hasValidRefreshToken()) return refresh().accessToken();
        return requestAndAuthenticateSmartOtp().accessToken();
    }

    public String ensureAuthenticatedForReconnect() {
        if (hasValidToken()) return token.accessToken();
        if (hasValidRefreshToken()) return refresh().accessToken();
        throw new AuthenticationException("SSI authentication is required before reconnect; no valid access or refresh token is available");
    }

    public String refreshForReconnect() {
        if (!hasValidRefreshToken()) throw new AuthenticationException("SSI authentication is required before reconnect; no valid refresh token is available");
        return refresh().accessToken();
    }

    public Token token() { return token; }

    public boolean hasValidToken() {
        Token current = token;
        return current != null && current.accessToken() != null && !current.accessToken().isBlank() && !current.isExpired(Instant.now().getEpochSecond());
    }

    public boolean hasValidRefreshToken() {
        Token current = token;
        return current != null && current.hasValidRefreshToken(Instant.now().getEpochSecond());
    }

    private Token authenticateInternal(TokenRequest request) {
        validateCredentials();
        RestClient.ApiResponse response;
        try {
            response = restClient.post(ACCESS_TOKEN_PATH, request);
        } catch (AuthenticationException authenticationFailure) {
            if (isSmartOtpPending(authenticationFailure.statusCode(), authenticationFailure.responseBody())) {
                throw new SmartOtpPendingException(
                        authenticationFailure.statusCode(),
                        authenticationFailure.responseBody());
            }
            throw authenticationFailure;
        }
        if (isSmartOtpPending(response)) throw new SmartOtpPendingException(response.statusCode(), response.body());
        Token parsed = parseToken(response.body(), "authenticating");
        setToken(parsed);
        return parsed;
    }

    private Token parseToken(JsonNode response, String context) {
        JsonNode payload = response != null && response.has("data") ? response.path("data") : response;
        if (payload == null || payload.isNull() || !payload.isObject()) throw new SsiApiException("Unexpected SSI token payload while " + context, 0, response);
        try {
            Token parsed = objectMapper.treeToValue(payload, Token.class);
            if (parsed.accessToken() == null || parsed.accessToken().isBlank()) throw new SsiApiException("SSI token payload is missing accessToken while " + context, 0, response);
            return parsed;
        } catch (JsonProcessingException e) { throw new SsiApiException("Unable to parse SSI token while " + context, e); }
    }

    private void setToken(Token token) { this.token = token; restClient.setAccessToken(token.accessToken()); log.info("SSI access token updated; expiresAt={}", token.expiresAt()); }
    private boolean isSmartOtpPending(RestClient.ApiResponse response) {
        return isSmartOtpPending(response.statusCode(), response.body());
    }
    private boolean isSmartOtpPending(int statusCode, JsonNode body) {
        return statusCode == SMART_OTP_PENDING_STATUS
                || body != null && (
                        body.path("code").asInt(-1) == SMART_OTP_PENDING_CODE
                        || body.path("status").asInt(-1) == SMART_OTP_PENDING_STATUS);
    }
    private String text(JsonNode node, String field) { return node == null || node.isNull() ? "" : node.path(field).asText(""); }
    private void validateCredentials() { if (config.apiKey() == null || config.apiKey().isBlank() || config.apiSecret() == null || config.apiSecret().isBlank()) throw new AuthenticationException("apiKey and apiSecret are required for SSI authentication"); }
    private void sleep(long millis) { try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AuthenticationException("Interrupted while waiting for Smart OTP approval"); } }

    private static final class SmartOtpPendingException extends AuthenticationException {
        private SmartOtpPendingException(int statusCode, JsonNode body) { super("Smart OTP approval is pending", statusCode, body); }
    }
}
