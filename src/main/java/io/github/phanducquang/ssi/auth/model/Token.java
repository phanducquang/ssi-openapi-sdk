package io.github.phanducquang.ssi.auth.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Token(
        @JsonProperty("accessToken") String accessToken,
        @JsonProperty("tokenType") String tokenType,
        @JsonProperty("expiresAt") long expiresAt,
        @JsonProperty("refreshToken") String refreshToken,
        @JsonProperty("refreshExpiresAt") long refreshTokenExpiresAt
) {
    public boolean isExpired(long epochSeconds) {
        return expiresAt > 0 && epochSeconds >= expiresAt;
    }

    public boolean hasRefreshToken() {
        return refreshToken != null && !refreshToken.isBlank();
    }

    public boolean isRefreshTokenExpired(long epochSeconds) {
        return refreshTokenExpiresAt > 0 && epochSeconds >= refreshTokenExpiresAt;
    }

    public boolean hasValidRefreshToken(long epochSeconds) {
        return hasRefreshToken() && !isRefreshTokenExpired(epochSeconds);
    }
}
