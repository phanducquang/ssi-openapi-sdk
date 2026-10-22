package vn.ssi.openapi.transport;

import java.net.http.HttpHeaders;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Parses delta-seconds and HTTP-date Retry-After values without logging response headers. */
public final class RetryAfter {
    private RetryAfter() {}
    public static Double seconds(HttpHeaders headers, Instant now) {
        return headers.firstValue("Retry-After").map(value -> {
            try {
                double seconds = Double.parseDouble(value.trim());
                return Double.isFinite(seconds) && seconds >= 0 ? seconds : null;
            } catch (NumberFormatException ignored) {
                try {
                    Instant deadline = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                    return Math.max(0, Duration.between(now, deadline).toMillis() / 1000.0);
                } catch (DateTimeParseException | ArithmeticException invalid) { return null; }
            }
        }).orElse(null);
    }
}
