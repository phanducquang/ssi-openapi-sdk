package vn.ssi.openapi.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import vn.ssi.openapi.auth.model.OtpRequest;
import vn.ssi.openapi.auth.model.OtpResponse;
import vn.ssi.openapi.auth.model.RefreshTokenRequest;
import vn.ssi.openapi.auth.model.Token;
import vn.ssi.openapi.auth.model.TokenRequest;
import vn.ssi.openapi.config.SsiConfig;
import vn.ssi.openapi.exception.AuthenticationException;
import vn.ssi.openapi.exception.SsiApiException;
import vn.ssi.openapi.transport.RestClient;
import vn.ssi.openapi.transport.RestTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.Clock;
import vn.ssi.openapi.config.ReconnectAuthenticationPolicy;
import vn.ssi.openapi.auth.model.TokenDiagnostics;
import vn.ssi.openapi.exception.InvalidTokenException;
import vn.ssi.openapi.exception.ReconnectAuthenticationException;
import vn.ssi.openapi.exception.RecoveryException;
import vn.ssi.openapi.streaming.model.RecoveryReason;
import vn.ssi.openapi.streaming.model.RecoveryStage;
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
    private final Clock clock;
    private volatile Token token;
    private volatile String rejectedAccessToken;
    private volatile String rejectedRefreshToken;

    public TokenManager(RestTransport restClient, SsiConfig config, ObjectMapper objectMapper) {
        this(restClient, config, objectMapper, Clock.systemUTC());
    }

    public TokenManager(RestTransport restClient, SsiConfig config, ObjectMapper objectMapper, Clock clock) {
        this.clock = Objects.requireNonNull(clock);
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
        long now = Instant.now(clock).getEpochSecond();
        if (current == null || !current.hasValidRefreshToken(now)) throw new AuthenticationException("No valid refresh token available; authenticate again");
        RestClient.ApiResponse response = restClient.post(REFRESH_TOKEN_PATH, new RefreshTokenRequest(current.refreshToken()));
        Token refreshed = parseToken(response.body(), "refreshing token");
        setToken(refreshed);
        return refreshed;
    }

    public synchronized String ensureAuthenticated() {
        if (hasValidToken()) return token.accessToken();
        if (hasValidRefreshToken()) return refresh().accessToken();
        return authenticate().accessToken();
    }

    public synchronized String ensureAuthenticatedWithOtp(String otp) {
        if (hasValidToken()) return token.accessToken();
        if (hasValidRefreshToken()) return refresh().accessToken();
        return authenticateWithOtp(otp).accessToken();
    }

    public synchronized String ensureAuthenticatedWithSmartOtp(String transactionId) {
        if (hasValidToken()) return token.accessToken();
        if (hasValidRefreshToken()) return refresh().accessToken();
        return authenticateSmartOtp(transactionId).accessToken();
    }

    public synchronized String ensureAuthenticatedWithSmartOtpApproval() {
        if (hasValidToken()) return token.accessToken();
        if (hasValidRefreshToken()) return refresh().accessToken();
        return requestAndAuthenticateSmartOtp().accessToken();
    }

    public synchronized String ensureAuthenticatedForReconnect() {
        if (hasValidToken()) return token.accessToken();
        if (hasValidRefreshToken()) return refresh().accessToken();
        throw new AuthenticationException("SSI authentication is required before reconnect; no valid access or refresh token is available");
    }

    public synchronized String refreshForReconnect() {
        if (!hasValidRefreshToken()) throw new AuthenticationException("SSI authentication is required before reconnect; no valid refresh token is available");
        return refresh().accessToken();
    }

    /** Resolves one runtime recovery token. rejectedToken must be the token used in the failed handshake. */
    public synchronized ReconnectToken resolveReconnectToken(ReconnectAuthenticationPolicy policy,
                                                               String rejectedToken, boolean allowAuthentication) {
        return resolveReconnectToken(policy, rejectedToken, allowAuthentication, true);
    }

    public synchronized ReconnectToken resolveReconnectToken(ReconnectAuthenticationPolicy policy,
                                                               String rejectedToken, boolean allowAuthentication, boolean allowRefresh) {
        Objects.requireNonNull(policy, "policy");
        if (rejectedToken != null && token != null && rejectedToken.equals(token.accessToken()))
            rejectedAccessToken = rejectedToken;
        if (hasValidToken()) return checkedReconnectToken(false);
        if (allowRefresh && hasValidRefreshToken()) {
            try {
                refresh();
                return checkedReconnectToken(false);
            } catch (AuthenticationException rejected) {
                if (token != null) rejectedRefreshToken = token.refreshToken();
                if (policy == ReconnectAuthenticationPolicy.REFRESH_ONLY)
                    throw new ReconnectAuthenticationException(RecoveryStage.REFRESH, RecoveryReason.REFRESH_REJECTED, rejected.statusCode());
            } catch (RuntimeException failure) {
                throw recoveryFailure(RecoveryStage.REFRESH, failure);
            }
        }
        if (policy == ReconnectAuthenticationPolicy.REFRESH_ONLY)
            throw new ReconnectAuthenticationException(RecoveryStage.TOKEN_LOOKUP, RecoveryReason.NO_USABLE_TOKEN, 0);
        if (!allowAuthentication)
            throw new ReconnectAuthenticationException(RecoveryStage.HANDSHAKE, RecoveryReason.HANDSHAKE_AUTH_REJECTED, 0);
        try {
            authenticate();
            return checkedReconnectToken(true);
        } catch (AuthenticationException rejected) {
            RecoveryReason reason = isSmartOtpPending(rejected.statusCode(), rejected.responseBody())
                    ? RecoveryReason.INTERACTION_REQUIRED : RecoveryReason.AUTHENTICATION_REJECTED;
            throw new ReconnectAuthenticationException(RecoveryStage.AUTHENTICATE, reason, rejected.statusCode());
        } catch (RuntimeException failure) {
            throw recoveryFailure(RecoveryStage.AUTHENTICATE, failure);
        }
    }

    /** Refresh only if the failed request still used the currently cached token. */
    public synchronized String refreshAfterRejection(String rejectedToken) {
        return resolveReconnectToken(ReconnectAuthenticationPolicy.REFRESH_ONLY, rejectedToken, false).accessToken();
    }

    private ReconnectToken checkedReconnectToken(boolean authenticated) {
        if (!hasValidToken()) throw new InvalidTokenException();
        return new ReconnectToken(token.accessToken(), authenticated);
    }

    private RecoveryException recoveryFailure(RecoveryStage stage, RuntimeException failure) {
        return new RecoveryException(stage, failure instanceof InvalidTokenException
                ? RecoveryReason.INVALID_TOKEN_RESPONSE : RecoveryReason.TRANSIENT_FAILURE, failure);
    }

    public record ReconnectToken(String accessToken, boolean authenticated) {
        @Override public String toString() { return "ReconnectToken[accessToken=<redacted>, authenticated=" + authenticated + "]"; }
    }

    public Token token() { return token; }

    public boolean hasValidToken() {
        Token current = token;
        return current != null && current.accessToken() != null && !current.accessToken().isBlank()
                && !current.accessToken().equals(rejectedAccessToken) && !current.isExpired(Instant.now(clock).getEpochSecond());
    }

    public boolean hasValidRefreshToken() {
        Token current = token;
        return current != null && !Objects.equals(current.refreshToken(), rejectedRefreshToken)
                && current.hasValidRefreshToken(Instant.now(clock).getEpochSecond());
    }

    public TokenDiagnostics diagnostics() {
        Token current = token;
        long now = Instant.now(clock).getEpochSecond();
        boolean present = current != null && current.accessToken() != null && !current.accessToken().isBlank();
        boolean refreshPresent = current != null && current.hasRefreshToken();
        return new TokenDiagnostics(present, present && !current.isExpired(now) && !Objects.equals(current.accessToken(), rejectedAccessToken),
                current == null ? 0 : current.expiresAt(), refreshPresent,
                refreshPresent && current.hasValidRefreshToken(now) && !Objects.equals(current.refreshToken(), rejectedRefreshToken),
                current == null ? 0 : current.refreshTokenExpiresAt());
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
        if (payload == null || payload.isNull() || !payload.isObject()) throw new InvalidTokenException();
        try {
            Token parsed = objectMapper.treeToValue(payload, Token.class);
            if (parsed.accessToken() == null || parsed.accessToken().isBlank()) throw new InvalidTokenException();
            return parsed;
        } catch (JsonProcessingException e) { throw new InvalidTokenException(); }
    }

    private void setToken(Token token) { this.token = token; rejectedAccessToken = null; rejectedRefreshToken = null; restClient.setAccessToken(token.accessToken()); log.info("SSI access token updated; expiresAt={}", token.expiresAt()); }
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
