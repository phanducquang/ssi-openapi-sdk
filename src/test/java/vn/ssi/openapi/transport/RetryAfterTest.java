package vn.ssi.openapi.transport;

import org.junit.jupiter.api.Test;
import java.net.http.HttpHeaders;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RetryAfterTest {
    private Double parse(String value) {
        return RetryAfter.seconds(HttpHeaders.of(Map.of("Retry-After", List.of(value)), (a,b) -> true), Instant.parse("2026-10-14T00:00:00Z"));
    }
    @Test void numericAndHttpDateDelaysAreSupported() {
        assertEquals(2.5, parse("2.5"));
        assertEquals(5.0, parse("Wed, 14 Oct 2026 00:00:05 GMT"));
        assertEquals(0.0, parse("Tue, 13 Oct 2026 00:00:00 GMT"));
    }
    @Test void invalidDelaysCannotEnterTheScheduler() {
        for (String value : List.of("NaN", "Infinity", "-2", "unknown")) assertNull(parse(value));
    }
}
