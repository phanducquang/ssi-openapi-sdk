package io.github.phanducquang.ssi.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.phanducquang.ssi.marketdata.enums.Board;
import io.github.phanducquang.ssi.streaming.enums.Timeframe;
import io.github.phanducquang.ssi.transport.RestClient;
import io.github.phanducquang.ssi.transport.RestTransport;
import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketDataServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsHistoricalOhlcAndBuildsExpectedQuery() throws Exception {
        RecordingTransport transport = new RecordingTransport(objectMapper.readTree("""
                {
                  "data": [
                    {
                      "symbol":"VNM",
                      "tradingDate":"2026/09/17 09:01:00",
                      "open":61.1,
                      "high":62.0,
                      "low":60.8,
                      "close":61.9,
                      "volume":1200,
                      "value":74280000
                    }
                  ]
                }
                """));

        var result = new MarketDataService(transport)
                .getOhlc("VNM", Timeframe.MINUTE_1, "2026/09/16 00:00:00",
                        "2026/09/17 23:59:59", 2, 500);

        assertEquals(1, result.size());
        assertEquals("VNM", result.get(0).symbol());
        assertEquals(61.9d, result.get(0).closePrice());
        assertEquals(1200L, result.get(0).volume());
        assertEquals(MarketDataService.OHLC_PATH, transport.paths.get(0));
        assertEquals("VNM", transport.queries.get(0).get("symbol"));
        assertEquals("1m", transport.queries.get(0).get("timeFrame"));
        assertEquals(2, transport.queries.get(0).get("pageIndex"));
        assertEquals(500, transport.queries.get(0).get("pageSize"));
    }

    @Test
    void filtersIndexesByBoard() throws Exception {
        RecordingTransport transport = new RecordingTransport(objectMapper.readTree("""
                [
                  {"index":"VN30","indexName":"VN30 Index","board":"HOSE"}
                ]
                """));

        var result = new MarketDataService(transport).getIndexesByBoard(Board.HOSE);

        assertEquals(1, result.size());
        assertEquals("VN30", result.get(0).index());
        assertEquals(Board.HOSE, result.get(0).board());
        assertEquals("HOSE", transport.queries.get(0).get("board"));
    }

    @Test
    void fetchesEveryMasterDataPage() throws Exception {
        RecordingTransport transport = new RecordingTransport(
                objectMapper.readTree("""
                        {
                          "data":[{"board":"HOSE","symbol":"AAA","tradingDate":"2026/09/17",
                                   "ceiling":12.0,"floor":10.0,"refPrice":11.0}],
                          "pagesCount":2
                        }
                        """),
                objectMapper.readTree("""
                        {
                          "data":[{"board":"HNX","symbol":"BBB","tradingDate":"2026/09/17",
                                   "ceiling":22.0,"floor":18.0,"refPrice":20.0}],
                          "pagesCount":2
                        }
                        """));

        var result = new MarketDataService(transport)
                .getMasterDataHistorical("2026/09/17", "2026/09/17");

        assertEquals(2, result.size());
        assertEquals("AAA", result.get(0).symbol());
        assertEquals("BBB", result.get(1).symbol());
        assertEquals(1, transport.queries.get(0).get("pageIndex"));
        assertEquals(2, transport.queries.get(1).get("pageIndex"));
        assertEquals(MarketDataService.MASTER_DATA_PATH, transport.paths.get(0));
        assertEquals(MarketDataService.MASTER_DATA_PATH, transport.paths.get(1));
    }

    private static final class RecordingTransport implements RestTransport {
        private final Queue<JsonNode> responses = new ArrayDeque<>();
        private final List<String> paths = new ArrayList<>();
        private final List<Map<String, ?>> queries = new ArrayList<>();

        private RecordingTransport(JsonNode... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public RestClient.ApiResponse get(String path, Map<String, ?> queryParams) {
            paths.add(path);
            queries.add(Map.copyOf(queryParams));
            return new RestClient.ApiResponse(
                    200,
                    responses.remove(),
                    HttpHeaders.of(Map.of(), (name, value) -> true));
        }

        @Override
        public RestClient.ApiResponse post(String path, Object body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setAccessToken(String token) {
        }

        @Override
        public void close() {
        }
    }
}
