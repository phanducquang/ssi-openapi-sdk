package vn.ssi.openapi.auth.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OtpRequest(@JsonProperty("apiKey") String apiKey, @JsonProperty("apiSecret") String apiSecret) {}
