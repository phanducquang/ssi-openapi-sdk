package vn.ssi.openapi.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.ssi.openapi.transport.RestClient;
import vn.ssi.openapi.transport.RestTransport;

import java.net.http.HttpHeaders;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class CorporateActionsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapsOnePageAndPreservesBlankFields() throws Exception {
        FakeTransport transport = new FakeTransport(mapper.readTree("""
                {"pageSize":20,"pageIndex":1,"pagesCount":1,"itemsCount":2,"data":[
                  {"symbol":"GDA","eventTitle":"Cổ tức tiền mặt","exrightDate":"2026/10/12",
                   "recordDate":"2026/10/13","issueDate":"2026/10/30","exchange":"HOSE","ratio":"0.9388"},
                  {"symbol":"SSI","eventTitle":"Pending event","exrightDate":"","recordDate":"",
                   "issueDate":"","exchange":"HOSE","ratio":""}
                ]}
                """));
        var page = new MarketDataService(transport)
                .getCorporateActionsPage("gda", "2026/10/10", "2026/10/15", "en", 1, 20);

        assertEquals(MarketDataService.CORPORATE_ACTIONS_PATH, transport.paths.get(0));
        assertEquals(Map.of("symbol","GDA","from","2026/10/10","to","2026/10/15",
                "language","en","pageIndex",1,"pageSize",20), transport.queries.get(0));
        assertEquals(2, page.itemsCount());
        assertEquals(2, page.data().size());
        assertEquals("Cổ tức tiền mặt", page.data().get(0).eventTitle());
        assertEquals("0.9388", page.data().get(0).ratio());
        assertEquals("", page.data().get(1).exrightDate());
        assertEquals("", page.data().get(1).ratio());
        assertThrows(UnsupportedOperationException.class, () -> page.data().clear());
    }

    @Test
    void fetchesEveryPageUsingServerPagesCount() throws Exception {
        FakeTransport transport = new FakeTransport(
                mapper.readTree("""
                        {"pageIndex":1,"pagesCount":2,"itemsCount":2,
                          "data":[{"symbol":"AAA","eventTitle":"A"}]}
                        """),
                mapper.readTree("""
                        {"pageIndex":2,"pagesCount":2,"itemsCount":2,
                          "data":[{"symbol":"BBB","eventTitle":"B"}]}
                        """));
        var list = new MarketDataService(transport)
                .getCorporateActions(null, "2026/10/10", "2026/10/15");

        assertEquals(List.of("AAA","BBB"), list.stream().map(a -> a.symbol()).toList());
        assertEquals(List.of(1,2), transport.queries.stream().map(q -> q.get("pageIndex")).toList());
        assertEquals(100, transport.queries.get(0).get("pageSize"));
        assertEquals("vn", transport.queries.get(0).get("language"));
        assertFalse(transport.queries.get(0).containsKey("symbol"));
    }

    @Test
    void handlesEmptyResultWithoutAnExtraRequest() throws Exception {
        FakeTransport transport = new FakeTransport(mapper.readTree("""
                {"pageIndex":1,"pagesCount":0,"itemsCount":0,"data":[]}
                """));
        assertTrue(new MarketDataService(transport)
                .getCorporateActions("VNM","2026/10/10","2026/10/10").isEmpty());
        assertEquals(1, transport.paths.size());
    }

    @Test
    void rejectsBadInputsBeforeCallingServer() {
        FakeTransport transport = new FakeTransport();
        MarketDataService service = new MarketDataService(transport);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> service.getCorporateActions("VNM","2026-10-10","2026/10/11")),
                () -> assertThrows(IllegalArgumentException.class, () -> service.getCorporateActions("VNM","2026/10/13","2026/10/12")),
                () -> assertThrows(IllegalArgumentException.class, () -> service.getCorporateActions("VNM","2026/10/10","2026/10/16")),
                () -> assertThrows(IllegalArgumentException.class, () -> service.getCorporateActions("V,NM","2026/10/10","2026/10/10")),
                () -> assertThrows(IllegalArgumentException.class, () -> service.getCorporateActionsPage("VNM","2026/10/10","2026/10/10","fr",1,10)),
                () -> assertThrows(IllegalArgumentException.class, () -> service.getCorporateActionsPage("VNM","2026/10/10","2026/10/10","vn",0,10)),
                () -> assertThrows(IllegalArgumentException.class, () -> service.getCorporateActionsPage("VNM","2026/10/10","2026/10/10","vn",1,30)));
        assertTrue(transport.paths.isEmpty());
    }

    private static final class FakeTransport implements RestTransport {
        private final Queue<JsonNode> responses = new ArrayDeque<>();
        private final List<String> paths = new ArrayList<>();
        private final List<Map<String, ?>> queries = new ArrayList<>();

        private FakeTransport(JsonNode... json) { responses.addAll(List.of(json)); }

        @Override public RestClient.ApiResponse get(String path, Map<String, ?> params) {
            paths.add(path);
            queries.add(Map.copyOf(params));
            return new RestClient.ApiResponse(200, responses.remove(),
                    HttpHeaders.of(Map.of(), (name,value) -> true));
        }
        @Override public RestClient.ApiResponse post(String path, Object body) { throw new UnsupportedOperationException(); }
        @Override public void setAccessToken(String token) {}
        @Override public void close() {}
    }
}
