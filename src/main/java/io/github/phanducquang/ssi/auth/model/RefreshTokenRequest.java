package io.github.phanducquang.ssi.auth.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RefreshTokenRequest(@JsonProperty("refreshToken") String refreshToken) {}
