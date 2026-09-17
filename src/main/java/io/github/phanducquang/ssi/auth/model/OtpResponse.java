package io.github.phanducquang.ssi.auth.model;

import com.fasterxml.jackson.databind.JsonNode;

public record OtpResponse(String transactionId, JsonNode raw) {}
