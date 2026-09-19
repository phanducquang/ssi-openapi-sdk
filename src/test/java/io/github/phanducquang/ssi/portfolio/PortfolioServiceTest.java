package io.github.phanducquang.ssi.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.phanducquang.ssi.config.SsiConfig;
import io.github.phanducquang.ssi.trading.enums.OrderSide;
import io.github.phanducquang.ssi.trading.enums.OrderStatus;
import io.github.phanducquang.ssi.trading.enums.OrderType;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PortfolioServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsBalanceAndSendsClientId() throws Exception {
        RecordingTransport transport = new RecordingTransport(objectMapper.readTree("""
                {
                  "equity": {
                    "accountNo":"1234567",
                    "accountBalance":"1000000.5",
                    "totalDebt":100000,
                    "withdrawable":800000
                  },
                  "derivative": {
                    "accountNo":"1234568",
                    "accountBalance":2000000,
                    "floatingPL":"1500.5"
                  }
                }
                """));
        PortfolioService service = service(transport);

        var balance = service.getEquityBalance("1234567");

        assertNotNull(balance);
        assertEquals("1234567", balance.accountNo());
        assertEquals(1000000.5d, balance.accountBalance());
        assertEquals(800000d, balance.withdrawable());
        assertEquals(PortfolioService.ACCOUNT_BALANCE_PATH, transport.paths.get(0));
        assertEquals("CLIENT", transport.queries.get(0).get("clientId"));
        assertEquals("1234567", transport.queries.get(0).get("accountNo"));
    }

    @Test
    void mapsOrdersAndTradingEnums() throws Exception {
        RecordingTransport transport = new RecordingTransport(objectMapper.readTree("""
                {
                  "accountNo":"1234567",
                  "orderList":[
                    {
                      "clientRequestId":"REQ-1",
                      "orderId":"OID-1",
                      "symbol":"VNM",
                      "side":"B",
                      "orderType":"LO",
                      "price":"61000",
                      "avgPrice":"60950.5",
                      "quantity":100,
                      "osQuantity":20,
                      "filledQuantity":80,
                      "cancelQuantity":0,
                      "orderStatus":"PF",
                      "inputTime":"09:00:00",
                      "modifiedTime":"09:01:00",
                      "message":""
                    }
                  ],
                  "totalRecord":1
                }
                """));

        var orders = service(transport)
                .getHistoricalOrders("1234567", "2026/09/01", "2026/09/17");

        assertEquals(1, orders.size());
        var order = orders.get(0);
        assertEquals(OrderSide.BUY, order.side());
        assertEquals(OrderType.LO, order.orderType());
        assertEquals(OrderStatus.PARTIAL_FILLED, order.status());
        assertEquals(61000L, order.price());
        assertEquals(60950.5d, order.avgPrice());
        assertEquals("2026/09/01", transport.queries.get(0).get("from"));
        assertEquals(1000, transport.queries.get(0).get("pageSize"));
    }

    @Test
    void mapsEquityAndDerivativePositions() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "equity":[
                    {
                      "accountNo":"1234567",
                      "symbol":"VNM",
                      "quantity":"1000",
                      "sellableQuantity":800,
                      "costPrice":"60000.5"
                    }
                  ],
                  "derivative":{
                    "derOpenPositions":[
                      {
                        "accountNo":"1234568",
                        "symbol":"VN30F2610",
                        "long":2,
                        "short":0,
                        "net":2,
                        "bidAvgPrice":1500.5
                      }
                    ],
                    "derClosePositions":[
                      {
                        "accountNo":"1234568",
                        "symbol":"VN30F2609",
                        "long":0,
                        "short":1,
                        "net":-1
                      }
                    ]
                  }
                }
                """);

        RecordingTransport equityTransport = new RecordingTransport(response);
        var equities = service(equityTransport).getEquityPositions("1234567");
        assertEquals(1, equities.size());
        assertEquals("VNM", equities.get(0).symbol());
        assertEquals(1000L, equities.get(0).quantity());
        assertEquals(60000.5d, equities.get(0).costPrice());

        RecordingTransport derivativeTransport = new RecordingTransport(response);
        var derivatives = service(derivativeTransport).getDerivativePositions("1234568");
        assertEquals(1, derivatives.openPositions().size());
        assertEquals(1, derivatives.closedPositions().size());
        assertEquals("VN30F2610", derivatives.openPositions().get(0).symbol());
        assertEquals(-1L, derivatives.closedPositions().get(0).net());
    }

    @Test
    void mapsPpmmrFields() throws Exception {
        RecordingTransport equityTransport = new RecordingTransport(objectMapper.readTree("""
                {
                  "equity":{
                    "accountNo":"1234567",
                    "purchasingPower":"900000.5",
                    "marginRatio":"75.2",
                    "liabilitySSI":120000,
                    "D":3000
                  }
                }
                """));

        var equity = service(equityTransport).getEquityPpmmr("1234567");
        assertNotNull(equity);
        assertEquals(900000.5d, equity.purchasingPower());
        assertEquals(75.2d, equity.marginRatio());
        assertEquals(120000d, equity.liabilitySsi());
        assertEquals(3000d, equity.d());

        RecordingTransport derivativeTransport = new RecordingTransport(objectMapper.readTree("""
                {
                  "derivative":{
                    "accountNo":"1234568",
                    "accountBalance":2000000,
                    "marginReqSSI":"350000.5",
                    "accountRatioVSDC":"68.5"
                  }
                }
                """));

        var derivative = service(derivativeTransport).getDerivativePpmmr("1234568");
        assertNotNull(derivative);
        assertEquals(350000.5d, derivative.marginReqSsi());
        assertEquals(68.5d, derivative.accountRatioVsdc());
        assertEquals(PortfolioService.PPMMR_PATH, derivativeTransport.paths.get(0));
        assertEquals("1234568", derivativeTransport.queries.get(0).get("accountNo"));
    }

    private PortfolioService service(RecordingTransport transport) {
        return new PortfolioService(
                transport,
                SsiConfig.builder().clientId("CLIENT").build());
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
