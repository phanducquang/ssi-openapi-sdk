package vn.ssi.openapi.streaming.model;

import java.time.Duration;

public record ReconnectEvent(int attempt, int maxAttempts, Duration delay) {}
