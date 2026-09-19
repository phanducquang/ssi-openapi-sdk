package io.github.phanducquang.ssi.trading.fco;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.phanducquang.ssi.config.SsiConfig;
import io.github.phanducquang.ssi.trading.TradingService;
import io.github.phanducquang.ssi.trading.enums.OrderSide;
import io.github.phanducquang.ssi.trading.enums.OrderStatus;
import io.github.phanducquang.ssi.trading.enums.OrderType;
import io.github.phanducquang.ssi.transport.RestClient;
import io.github.phanducquang.ssi.transport.RestTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FcoServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private String privateKey;

    @BeforeEach
    void setUpKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var key = (RSAPrivateKey) generator.generateKeyPair().getPrivate();

        String xml = "<RSAKeyValue>"
                + "<Modulus>" + Base64.getEncoder().encodeToString(unsigned(key.getModulus())) + "</Modulus>"
                + "<D>" + Base64.getEncoder().encodeToString(unsigned(key.getPrivateExponent())) + "</D>"
                + "</RSAKeyValue>";
        privateKey = Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesFcoListAndFilters() throws Exception {
        RecordingTransport transport = new RecordingTransport(mapper.readTree("""
                {
                  "pageIndex":1,
                  "pageSize":10,
                  "itemsCount":1,
                  "pagesCount":1,
                  "data":[{
                    "fcoId":"FCO-1",
                    "username":"client",
                    "accountNo":"1234567",
                    "quantity":"100",
                    "price":"61000",
                    "priceSlip":"0",
                    "symbol":"VNM",
                    "type":"stop",
                    "from":"2026/09/19",
                    "to":"2026/09/30",
                    "matchedQuantity":20,
                    "isPlaceOrder":true,
                    "status":"WAIT",
                    "detail":"",
                    "params":{"stopPrice":"60000","side":"B","operator":"lesser_or_equal"}
                  }]
                }
                """));

        FcoListResponse result = service(transport).getFcoBySymbol("1234567", "VNM", 1, 10);

        assertEquals(1, result.fcoList().size());
        FcoInfo item = result.fcoList().get(0);
        assertEquals("FCO-1", item.fcoId());
        assertEquals(FcoType.STOP, item.type());
        assertEquals(FcoStatus.WAIT, item.status());
        assertEquals(OrderSide.BUY, item.params().side());
        assertEquals(FcoOperator.LESSER_OR_EQUAL, item.params().operator());
        assertEquals("VNM", transport.queries.get(0).get("symbol"));
    }

    @Test
    void parsesFcoOrderBook() throws Exception {
        RecordingTransport transport = new RecordingTransport(mapper.readTree("""
                {
                  "pageIndex":1,
                  "pageSize":10,
                  "data":[{
                    "fcoId":"FCO-1",
                    "accountNo":"1234567",
                    "quantity":"100",
                    "price":"61000",
                    "symbol":"VNM",
                    "side":"B",
                    "orderType":"LO",
                    "isMainOrder":true,
                    "isAttachedOrder":false,
                    "orderId":"OID-1",
                    "matchedQuantity":80,
                    "osQuantity":20,
                    "avgPrice":"60950.5",
                    "status":"PF"
                  }]
                }
                """));

        FcoOrderBookResponse result = service(transport).getFcoOrderBook("FCO-1");

        assertEquals(1, result.orderBook().size());
        FcoOrder order = result.orderBook().get(0);
        assertEquals(OrderSide.BUY, order.side());
        assertEquals(OrderType.LO, order.orderType());
        assertEquals(OrderStatus.PARTIAL_FILLED, order.status());
        assertEquals(60950.5d, order.avgPrice());
    }

    @Test
    void placesSignedStopLimitAndCancelsFco() throws Exception {
        RecordingTransport placeTransport = new RecordingTransport(mapper.readTree("""
                {"fcoId":"FCO-2"}
                """));

        FcoPlaceResponse placed = service(placeTransport).placeFcoStopLimit(
                "1234567",
                "VNM",
                OrderSide.SELL,
                100,
                61000,
                0,
                60500,
                FcoOperator.LESSER_OR_EQUAL,
                "2026/09/19",
                "2026/09/30");

        assertEquals("FCO-2", placed.fcoId());
        assertEquals("POST", placeTransport.methods.get(0));
        assertEquals(FcoService.FCO_ORDER_PATH, placeTransport.paths.get(0));
        assertTrue(placeTransport.headers.get(0).containsKey(TradingService.SIGNATURE_HEADER));

        JsonNode body = mapper.readTree((String) placeTransport.bodies.get(0));
        assertEquals("stop_limit", body.path("type").asText());
        assertEquals("S", body.path("side").asText());
        assertEquals("61000", body.path("price").asText());
        assertEquals(60500, body.path("stopPrice").asInt());
        assertEquals("lesser_or_equal", body.path("operator").asText());

        RecordingTransport cancelTransport = new RecordingTransport(mapper.readTree("""
                {"fcoId":"FCO-2"}
                """));
        FcoCancelResponse cancelled = service(cancelTransport).cancelFco("FCO-2");

        assertEquals("FCO-2", cancelled.fcoId());
        assertEquals("DELETE", cancelTransport.methods.get(0));
        assertTrue(cancelTransport.headers.get(0).containsKey(TradingService.SIGNATURE_HEADER));
    }

    @Test
    void buildsTypedOcoPricesAndZeroesSlipForOrderTypes() throws Exception {
        RecordingTransport transport = new RecordingTransport(mapper.readTree("""
                {"fcoId":"FCO-3"}
                """));

        service(transport).placeFcoOco(
                "1234567",
                "VNM",
                OrderSide.SELL,
                100,
                65000,
                59000,
                FcoPrice.orderType(OrderType.MTL),
                FcoPrice.fixed(58500),
                200,
                100,
                "2026/09/19",
                "2026/09/30");

        JsonNode body = mapper.readTree((String) transport.bodies.get(0));
        assertEquals("oco", body.path("type").asText());
        assertEquals("MTL", body.path("tpPrice").asText());
        assertEquals(0, body.path("tpSlip").asInt());
        assertEquals("58500", body.path("slPrice").asText());
        assertEquals(100, body.path("slSlip").asInt());
        assertEquals("MP", body.path("price").asText());
    }

    private FcoService service(RecordingTransport transport) {
        return new FcoService(
                transport,
                SsiConfig.builder().privateKey(privateKey).build(),
                mapper);
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            return java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }

    private static final class RecordingTransport implements RestTransport {
        private final Queue<JsonNode> responses = new ArrayDeque<>();
        private final List<String> methods = new ArrayList<>();
        private final List<String> paths = new ArrayList<>();
        private final List<Map<String, ?>> queries = new ArrayList<>();
        private final List<Object> bodies = new ArrayList<>();
        private final List<Map<String, String>> headers = new ArrayList<>();

        private RecordingTransport(JsonNode... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public RestClient.ApiResponse request(
                String method,
                String path,
                Map<String, ?> queryParams,
                Object body,
                Map<String, String> headers) {
            methods.add(method);
            paths.add(path);
            queries.add(Map.copyOf(queryParams));
            bodies.add(body);
            this.headers.add(Map.copyOf(headers));
            return new RestClient.ApiResponse(
                    200,
                    responses.remove(),
                    HttpHeaders.of(Map.of(), (name, value) -> true));
        }

        @Override
        public RestClient.ApiResponse post(String path, Object body) {
            return request("POST", path, Map.of(), body, Map.of());
        }

        @Override
        public void setAccessToken(String token) {
        }

        @Override
        public void close() {
        }
    }
}
