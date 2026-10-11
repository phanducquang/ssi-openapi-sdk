package vn.ssi.openapi.auth.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RefreshTokenRequest(@JsonProperty("refreshToken") String refreshToken) {}
