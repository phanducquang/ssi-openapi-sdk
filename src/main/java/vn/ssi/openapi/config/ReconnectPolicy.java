package vn.ssi.openapi.config;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/** Runtime reconnect budget, independent of REST and initial-connect retries. */
public record ReconnectPolicy(int maxAttempts, Duration initialDelay, Duration maxDelay, double jitter) {
    public ReconnectPolicy {
        if (maxAttempts != -1 && maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive or -1 (unlimited)");
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (initialDelay.isNegative() || maxDelay.isNegative() || maxDelay.toMillis() < 1 || initialDelay.compareTo(maxDelay) > 0)
            throw new IllegalArgumentException("Reconnect delays must be nonnegative and maxDelay >= initialDelay and >= 1ms");
        if (!Double.isFinite(jitter) || jitter < 0 || jitter > 1) throw new IllegalArgumentException("jitter must be in [0, 1]");
    }

    public boolean canAttempt(int attempt) { return maxAttempts == -1 || attempt <= maxAttempts; }

    public Duration delayForAttempt(int attempt) {
        if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
        long base = initialDelay.toMillis(), cap = maxDelay.toMillis();
        long factor = 1L << Math.min(attempt - 1, 62);
        long millis = base == 0 ? 0 : factor > cap / base ? cap : Math.min(cap, base * factor);
        if (jitter > 0 && millis > 0)
            millis = Math.min(cap, Math.max(0L, (long) (millis * (1 + ThreadLocalRandom.current().nextDouble(-jitter, jitter)))));
        return Duration.ofMillis(millis);
    }
}
