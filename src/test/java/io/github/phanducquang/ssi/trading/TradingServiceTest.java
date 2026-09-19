package io.github.phanducquang.ssi.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.phanducquang.ssi.config.SsiConfig;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String privateKey;

    @BeforeEach
    void setUpKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keyPair = generator.generateKeyPair();
        var key = (RSAPrivateKey) keyPair.getPrivate();

        String xml = "<RSAKeyValue>"
                + "<Modulus>" + Base64.getEncoder().encodeToString(unsigned(key.getModulus())) + "</Modulus>"
                + "<D>" + Base64.getEncoder().encodeToString(unsigned(key.getPrivateExponent())) + "</D>"
                + "</RSAKeyValue>";
        privateKey = Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void placesSignedLimitOrderUsingExactRawBody() throws Exception {
        RecordingTransport transport = new RecordingTransport(objectMapper.readTree("""
                {"orderId":"OID-1","clientRequestId":"REQ-SERVER","orderStatus":"PD"}
                """));

        var response = service(transport)
                .placeLimitOrder("1234567", "VNM", OrderSide.BUY, 100, 61000);

        assertEquals(OrderStatus.PENDING, response.status());
        assertEquals("POST", transport.methods.get(0));
        assertEquals(TradingService.ORDER_PATH, transport.paths.get(0));

        String rawBody = (String) transport.bodies.get(0);
        JsonNode body = objectMapper.readTree(rawBody);
        assertEquals("1234567", body.path("accountNo").asText());
        assertEquals("VNM", body.path("symbol").asText());
        assertEquals("B", body.path("side").asText());
        assertEquals(100, body.path("quantity").asInt());
        assertEquals("61000", body.path("price").asText());
        assertEquals("LO", body.path("orderType").asText());
        assertEquals(20, body.path("clientRequestId").asText().length());
        assertEquals("A1:B2:C3:D4:E5:F6", body.path("deviceId").asText());

        String signature = transport.headers.get(0).get(TradingService.SIGNATURE_HEADER);
        assertNotNull(signature);
        assertFalse(signature.isBlank());
    }

    @Test
    void modifiesPriceByOrderIdAndQuantityByClientRequestId() throws Exception {
        RecordingTransport priceTransport = new RecordingTransport(objectMapper.readTree("""
                {"clientModifyId":"M-1","orderId":"OID-1","clientRequestId":"","orderStatus":"WM"}
                """));

        var priceResponse = service(priceTransport)
                .modifyOrderPriceByOrderId("1234567", "OID-1", 62000);

        assertEquals(OrderStatus.PENDING_MODIFY, priceResponse.status());
        assertEquals("PUT", priceTransport.methods.get(0));
        JsonNode priceBody = objectMapper.readTree((String) priceTransport.bodies.get(0));
        assertEquals("OID-1", priceBody.path("orderId").asText());
        assertEquals("62000", priceBody.path("price").asText());
        assertTrue(priceBody.path("clientRequestId").isMissingNode());

        RecordingTransport quantityTransport = new RecordingTransport(objectMapper.readTree("""
                {"clientModifyId":"M-2","orderId":"OID-2","clientRequestId":"REQ-1","orderStatus":"WM"}
                """));

        service(quantityTransport)
                .modifyOrderQuantity("1234567", "REQ-1", 200);

        JsonNode quantityBody = objectMapper.readTree((String) quantityTransport.bodies.get(0));
        assertEquals("REQ-1", quantityBody.path("clientRequestId").asText());
        assertEquals(200, quantityBody.path("quantity").asInt());
        assertTrue(quantityBody.path("price").isMissingNode());
    }

    @Test
    void cancelsByClientRequestId() throws Exception {
        RecordingTransport transport = new RecordingTransport(objectMapper.readTree("""
                {"clientCancelId":"C-1","orderId":"OID-1","clientRequestId":"REQ-1","orderStatus":"WC"}
                """));

        var response = service(transport).cancelOrder("1234567", "REQ-1");

        assertEquals(OrderStatus.PENDING_CANCEL, response.status());
        assertEquals("DELETE", transport.methods.get(0));
        JsonNode body = objectMapper.readTree((String) transport.bodies.get(0));
        assertEquals("REQ-1", body.path("clientRequestId").asText());
        assertEquals(20, body.path("clientCancelId").asText().length());
        assertTrue(transport.headers.get(0).containsKey(TradingService.SIGNATURE_HEADER));
    }

    @Test
    void getsMaxBuySellWithNormalizedSymbolAndOptionalPrice() throws Exception {
        RecordingTransport pricedTransport = new RecordingTransport(objectMapper.readTree("""
                {
                  "accountNo":"1234567",
                  "maxBuyQty":"1000",
                  "maxSellQty":500,
                  "marginRatio":"50",
                  "purchasePower":"61000000"
                }
                """));

        var response = service(pricedTransport)
                .getMaxBuySell("1234567", "vnm", 61000);

        assertEquals("VNM", response.symbol());
        assertEquals(1000L, response.maxBuyQuantity());
        assertEquals(500L, response.maxSellQuantity());
        assertEquals("GET", pricedTransport.methods.get(0));
        assertEquals("VNM", pricedTransport.queries.get(0).get("symbol"));
        assertEquals("61000", pricedTransport.queries.get(0).get("price"));
        assertTrue(pricedTransport.headers.get(0).isEmpty());

        RecordingTransport marketTransport = new RecordingTransport(objectMapper.readTree("""
                {"accountNo":"1234567","maxBuyQty":900,"maxSellQty":400}
                """));

        service(marketTransport).getMaxBuySellAtMarketPrice("1234567", "VNM");
        assertFalse(marketTransport.queries.get(0).containsKey("price"));
    }

    private TradingService service(RecordingTransport transport) {
        return new TradingService(
                transport,
                SsiConfig.builder().privateKey(privateKey).build(),
                objectMapper);
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
