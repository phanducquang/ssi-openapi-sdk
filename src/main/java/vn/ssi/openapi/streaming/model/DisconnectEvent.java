package vn.ssi.openapi.streaming.model;

public record DisconnectEvent(int statusCode, String reason, boolean manual) {}
