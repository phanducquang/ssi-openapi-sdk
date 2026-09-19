package io.github.phanducquang.ssi.transport;

import io.github.phanducquang.ssi.auth.TokenManager;
import io.github.phanducquang.ssi.exception.AuthenticationException;

import java.util.Map;
import java.util.Objects;

public final class AuthenticatedRestClient implements RestTransport {
    private final RestTransport delegate;
    private final TokenManager tokenManager;

    public AuthenticatedRestClient(RestTransport delegate, TokenManager tokenManager) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.tokenManager = Objects.requireNonNull(tokenManager, "tokenManager");
    }

    @Override
    public RestClient.ApiResponse post(String path, Object body) {
        return request("POST", path, Map.of(), body, Map.of());
    }

    @Override
    public RestClient.ApiResponse request(
            String method,
            String path,
            Map<String, ?> queryParams,
            Object body,
            Map<String, String> headers) {
        tokenManager.ensureAuthenticated();

        try {
            return delegate.request(method, path, queryParams, body, headers);
        } catch (AuthenticationException firstFailure) {
            try {
                tokenManager.refresh();
            } catch (AuthenticationException refreshFailure) {
                firstFailure.addSuppressed(refreshFailure);
                throw firstFailure;
            }
            return delegate.request(method, path, queryParams, body, headers);
        }
    }

    @Override
    public void setAccessToken(String token) {
        delegate.setAccessToken(token);
    }

    @Override
    public void close() {
        // The underlying transport is owned and closed by SsiClient.
    }
}
