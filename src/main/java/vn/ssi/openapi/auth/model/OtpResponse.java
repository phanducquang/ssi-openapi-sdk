package vn.ssi.openapi.auth.model;

import com.fasterxml.jackson.databind.JsonNode;

public record OtpResponse(String transactionId, JsonNode raw) {}
