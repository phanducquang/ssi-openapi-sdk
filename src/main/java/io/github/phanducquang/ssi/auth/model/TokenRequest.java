package io.github.phanducquang.ssi.auth.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenRequest(@JsonProperty("apiKey") String apiKey, @JsonProperty("apiSecret") String apiSecret, String otp, @JsonProperty("transactionId") String transactionId) {}
