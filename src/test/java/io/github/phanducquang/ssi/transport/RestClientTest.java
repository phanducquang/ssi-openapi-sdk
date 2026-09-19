package io.github.phanducquang.ssi.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.phanducquang.ssi.config.SsiConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RestClientTest {
    @Test
    void encodesQueryParametersWithoutMutatingPath() {
        RestClient client = new RestClient(
                SsiConfig.builder()
                        .apiUrl(URI.create("https://example.test"))
                        .build(),
                new ObjectMapper());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("symbol", "VN 30");
        params.put("from", "2026/09/17 00:00:00");
        params.put("pageIndex", 1);
        params.put("ignored", null);

        URI uri = client.buildUri("/api/v3/data/ohlc", params);

        assertEquals(
                "https://example.test/api/v3/data/ohlc"
                        + "?symbol=VN+30"
                        + "&from=2026%2F09%2F17+00%3A00%3A00"
                        + "&pageIndex=1",
                uri.toString());
    }
}
