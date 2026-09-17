package io.github.phanducquang.ssi.streaming.model;

public record DisconnectEvent(int statusCode, String reason, boolean manual) {}
