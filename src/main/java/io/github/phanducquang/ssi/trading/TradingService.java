package io.github.phanducquang.ssi.trading;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.phanducquang.ssi.config.SsiConfig;
import io.github.phanducquang.ssi.exception.SsiApiException;
import io.github.phanducquang.ssi.trading.enums.OrderSide;
import io.github.phanducquang.ssi.trading.enums.OrderStatus;
import io.github.phanducquang.ssi.trading.enums.OrderType;
import io.github.phanducquang.ssi.trading.fco.*;
import io.github.phanducquang.ssi.trading.model.CancelOrderResponse;
import io.github.phanducquang.ssi.trading.model.MaxBuySellResponse;
import io.github.phanducquang.ssi.trading.model.ModifyOrderResponse;
import io.github.phanducquang.ssi.trading.model.PlaceOrderResponse;
import io.github.phanducquang.ssi.transport.RestClient;
import io.github.phanducquang.ssi.transport.RestTransport;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class TradingService {
    public static final String ORDER_PATH = "/api/v3/trading/order";
    public static final String MAX_BUY_SELL_PATH = "/api/v3/trading/maxBuySell";
    public static final String SIGNATURE_HEADER = "X-Signature";

    private static final String DEFAULT_DEVICE_ID = "A1:B2:C3:D4:E5:F6";
    private static final String USER_AGENT = "SSI Java SDK/0.1.0-SNAPSHOT";

    private final RestTransport restClient;
    private final ObjectMapper objectMapper;
    private final String privateKey;
    private final FcoService fcoService;

    public TradingService(RestTransport restClient, SsiConfig config, ObjectMapper objectMapper) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.privateKey = Objects.requireNonNull(config, "config").privateKey();
        this.fcoService = new FcoService(restClient, config, objectMapper);
    }

    public PlaceOrderResponse placeOrder(
            String accountNo,
            String symbol,
            OrderSide side,
            long quantity,
            double price,
            OrderType orderType) {
        requireText(accountNo, "accountNo");
        requireText(symbol, "symbol");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(orderType, "orderType");
        requirePositive(quantity, "quantity");
        requireNonNegative(price, "price");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountNo", accountNo);
        body.put("symbol", symbol);
        body.put("side", side.value());
        body.put("quantity", quantity);
        body.put("price", formatNumber(price));
        body.put("orderType", orderType.value());
        body.put("clientRequestId", RequestIdGenerator.generate());
        body.put("deviceId", DEFAULT_DEVICE_ID);
        body.put("userAgent", USER_AGENT);

        JsonNode response = signedRequest("POST", ORDER_PATH, body);
        return new PlaceOrderResponse(
                text(response, "orderId"),
                text(response, "clientRequestId"),
                text(response, "orderStatus"));
    }

    public PlaceOrderResponse placeLimitOrder(
            String accountNo,
            String symbol,
            OrderSide side,
            long quantity,
            double price) {
        requirePositive(price, "price");
        return placeOrder(accountNo, symbol, side, quantity, price, OrderType.LO);
    }

    public PlaceOrderResponse placeMarketOrder(
            String accountNo,
            String symbol,
            OrderSide side,
            long quantity) {
        return placeOrder(accountNo, symbol, side, quantity, 0.0d, OrderType.MTL);
    }

    public PlaceOrderResponse placeAtoOrder(
            String accountNo,
            String symbol,
            OrderSide side,
            long quantity) {
        return placeOrder(accountNo, symbol, side, quantity, 0.0d, OrderType.ATO);
    }

    public PlaceOrderResponse placeAtcOrder(
            String accountNo,
            String symbol,
            OrderSide side,
            long quantity) {
        return placeOrder(accountNo, symbol, side, quantity, 0.0d, OrderType.ATC);
    }

    public ModifyOrderResponse modifyOrderPrice(
            String accountNo,
            String clientRequestId,
            double price) {
        requireText(clientRequestId, "clientRequestId");
        requirePositive(price, "price");
        return modifyOrder(accountNo, null, clientRequestId, price, null);
    }

    public ModifyOrderResponse modifyOrderPriceByOrderId(
            String accountNo,
            String orderId,
            double price) {
        requireText(orderId, "orderId");
        requirePositive(price, "price");
        return modifyOrder(accountNo, orderId, null, price, null);
    }

    public ModifyOrderResponse modifyOrderQuantity(
            String accountNo,
            String clientRequestId,
            long quantity) {
        requireText(clientRequestId, "clientRequestId");
        requirePositive(quantity, "quantity");
        return modifyOrder(accountNo, null, clientRequestId, null, quantity);
    }

    public ModifyOrderResponse modifyOrderQuantityByOrderId(
            String accountNo,
            String orderId,
            long quantity) {
        requireText(orderId, "orderId");
        requirePositive(quantity, "quantity");
        return modifyOrder(accountNo, orderId, null, null, quantity);
    }

    public CancelOrderResponse cancelOrder(String accountNo, String clientRequestId) {
        requireText(clientRequestId, "clientRequestId");
        return cancelOrderInternal(accountNo, null, clientRequestId);
    }

    public CancelOrderResponse cancelOrderByOrderId(String accountNo, String orderId) {
        requireText(orderId, "orderId");
        return cancelOrderInternal(accountNo, orderId, null);
    }

    public MaxBuySellResponse getMaxBuySell(
            String accountNo,
            String symbol,
            double price) {
        requirePositive(price, "price");
        return getMaxBuySellInternal(accountNo, symbol, formatNumber(price));
    }

    public MaxBuySellResponse getMaxBuySellAtMarketPrice(
            String accountNo,
            String symbol) {
        return getMaxBuySellInternal(accountNo, symbol, null);
    }

    public FcoService fco() {
        return fcoService;
    }

    public FcoListResponse getFcoByAccountNo(String accountNo) {
        return fcoService.getFcoByAccountNo(accountNo);
    }

    public FcoListResponse getFcoByAccountNo(String accountNo, int pageIndex, int pageSize) {
        return fcoService.getFcoByAccountNo(accountNo, pageIndex, pageSize);
    }

    public FcoListResponse getFcoBySymbol(String accountNo, String symbol) {
        return fcoService.getFcoBySymbol(accountNo, symbol, 1, 10);
    }

    public FcoListResponse getFcoBySymbol(String accountNo, String symbol, int pageIndex, int pageSize) {
        return fcoService.getFcoBySymbol(accountNo, symbol, pageIndex, pageSize);
    }

    public FcoListResponse getFcoByStatus(String accountNo, String processStatus) {
        return fcoService.getFcoByStatus(accountNo, processStatus, 1, 10);
    }

    public FcoListResponse getFcoByStatus(String accountNo, String processStatus, int pageIndex, int pageSize) {
        return fcoService.getFcoByStatus(accountNo, processStatus, pageIndex, pageSize);
    }

    public FcoListResponse getFcoByDate(String accountNo, String fromDate, String toDate) {
        return fcoService.getFcoByDate(accountNo, fromDate, toDate, 1, 10);
    }

    public FcoListResponse getFcoByDate(
            String accountNo,
            String fromDate,
            String toDate,
            int pageIndex,
            int pageSize) {
        return fcoService.getFcoByDate(accountNo, fromDate, toDate, pageIndex, pageSize);
    }

    public FcoInfo getFcoById(String accountNo, String fcoId) {
        return fcoService.getFcoById(accountNo, fcoId);
    }

    public FcoOrderBookResponse getFcoOrderBook(String fcoId) {
        return fcoService.getFcoOrderBook(fcoId);
    }

    public FcoOrderBookResponse getFcoOrderBook(String fcoId, int pageIndex, int pageSize) {
        return fcoService.getFcoOrderBook(fcoId, pageIndex, pageSize);
    }

    public FcoPlaceResponse placeFcoGtd(
            String accountNo,
            String symbol,
            OrderSide side,
            long quantity,
            double price,
            double priceSlip,
            String fromDate,
            String toDate) {
        return fcoService.placeFcoGtd(
                accountNo, symbol, side, quantity, FcoPrice.fixed(price), priceSlip, fromDate, toDate);
    }

    public FcoPlaceResponse placeFcoGtd(
            String accountNo,
            String symbol,
            OrderSide side,
            long quantity,
            OrderType price,
            String fromDate,
            String toDate) {
        return fcoService.placeFcoGtd(
                accountNo, symbol, side, quantity, FcoPrice.orderType(price), 0, fromDate, toDate);
    }

    public FcoPlaceResponse placeFcoStop(
            String accountNo,
            String symbol,
            OrderSide side,
            long quantity,
            double stopPrice,
            FcoOperator operator,
            String fromDate,
            String toDate) {
        return fcoService.placeFcoStop(
                accountNo, symbol, side, quantity, stopPrice, operator, fromDate, toDate);
    }

    public FcoPlaceResponse placeFcoStopLimit(
            String accountNo,
            String symbol,
            OrderSide side,
            long quantity,
            double price,
            double priceSlip,
            double stopPrice,
            FcoOperator operator,
            String fromDate,
            String toDate) {
        return fcoService.placeFcoStopLimit(
                accountNo, symbol, side, quantity, price, priceSlip, stopPrice, operator, fromDate, toDate);
    }

    public FcoPlaceResponse placeFcoTrailingStop(
            String accountNo,
            String symbol,
            OrderSide side,
            long quantity,
            double activePrice,
            double trailingAmount,
            String fromDate,
            String toDate) {
        return fcoService.placeFcoTrailingStop(
                accountNo, symbol, side, quantity, activePrice, trailingAmount, fromDate, toDate);
    }

    public FcoPlaceResponse placeFcoTrailingStopLimit(
            String accountNo,
            String symbol,
            OrderSide side,
            long quantity,
            double activePrice,
            double trailingAmount,
            double priceSlip,
            String fromDate,
            String toDate) {
        return fcoService.placeFcoTrailingStopLimit(
                accountNo, symbol, side, quantity, activePrice, trailingAmount, priceSlip, fromDate, toDate);
    }

    public FcoPlaceResponse placeFcoOco(
            String accountNo,
            String symbol,
            OrderSide side,
            long quantity,
            double tpActivePrice,
            double slActivePrice,
            FcoPrice tpPrice,
            FcoPrice slPrice,
            double tpSlip,
            double slSlip,
            String fromDate,
            String toDate) {
        return fcoService.placeFcoOco(
                accountNo,
                symbol,
                side,
                quantity,
                tpActivePrice,
                slActivePrice,
                tpPrice,
                slPrice,
                tpSlip,
                slSlip,
                fromDate,
                toDate);
    }

    public FcoPlaceResponse placeFcoBullBear(
            String accountNo,
            String symbol,
            OrderSide side,
            long quantity,
            FcoPrice price,
            double priceSlip,
            double tpActivePrice,
            double slActivePrice,
            FcoPrice tpPrice,
            FcoPrice slPrice,
            double tpSlip,
            double slSlip,
            String fromDate,
            String toDate) {
        return fcoService.placeFcoBullBear(
                accountNo,
                symbol,
                side,
                quantity,
                price,
                priceSlip,
                tpActivePrice,
                slActivePrice,
                tpPrice,
                slPrice,
                tpSlip,
                slSlip,
                fromDate,
                toDate);
    }

    public FcoCancelResponse cancelFco(String fcoId) {
        return fcoService.cancelFco(fcoId);
    }

    private ModifyOrderResponse modifyOrder(
            String accountNo,
            String orderId,
            String clientRequestId,
            Double price,
            Long quantity) {
        requireText(accountNo, "accountNo");

        if ((price == null) == (quantity == null)) {
            throw new IllegalArgumentException("Exactly one of price or quantity must be provided");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountNo", accountNo);
        body.put("clientModifyId", RequestIdGenerator.generate());
        body.put("deviceId", DEFAULT_DEVICE_ID);
        body.put("userAgent", USER_AGENT);

        if (orderId != null) {
            body.put("orderId", orderId);
        }
        if (clientRequestId != null) {
            body.put("clientRequestId", clientRequestId);
        }
        if (quantity != null) {
            body.put("quantity", quantity);
        }
        if (price != null) {
            body.put("price", formatNumber(price));
        }

        JsonNode response = signedRequest("PUT", ORDER_PATH, body);
        return new ModifyOrderResponse(
                text(response, "clientModifyId"),
                text(response, "orderId"),
                text(response, "clientRequestId"),
                text(response, "orderStatus"));
    }

    private CancelOrderResponse cancelOrderInternal(
            String accountNo,
            String orderId,
            String clientRequestId) {
        requireText(accountNo, "accountNo");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountNo", accountNo);
        body.put("clientCancelId", RequestIdGenerator.generate());
        body.put("deviceId", DEFAULT_DEVICE_ID);
        body.put("userAgent", USER_AGENT);

        if (orderId != null) {
            body.put("orderId", orderId);
        }
        if (clientRequestId != null) {
            body.put("clientRequestId", clientRequestId);
        }

        JsonNode response = signedRequest("DELETE", ORDER_PATH, body);
        return new CancelOrderResponse(
                text(response, "clientCancelId"),
                text(response, "orderId"),
                text(response, "clientRequestId"),
                text(response, "orderStatus"));
    }

    private MaxBuySellResponse getMaxBuySellInternal(
            String accountNo,
            String symbol,
            String price) {
        requireText(accountNo, "accountNo");
        requireText(symbol, "symbol");

        String normalizedSymbol = symbol.toUpperCase(Locale.ROOT);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("accountNo", accountNo);
        params.put("symbol", normalizedSymbol);
        if (price != null) {
            params.put("price", price);
        }

        JsonNode response = unwrap(restClient.get(MAX_BUY_SELL_PATH, params).body());
        return new MaxBuySellResponse(
                text(response, "accountNo"),
                normalizedSymbol,
                integer(response, "maxBuyQty"),
                integer(response, "maxSellQty"),
                text(response, "marginRatio"),
                text(response, "purchasePower"));
    }

    private JsonNode signedRequest(
            String method,
            String path,
            Map<String, Object> body) {
        String rawBody;
        try {
            rawBody = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new SsiApiException("Failed to serialize SSI trading request", e);
        }

        String signature = RequestSigner.sign(rawBody, privateKey);
        RestClient.ApiResponse response = restClient.request(
                method,
                path,
                Map.of(),
                rawBody,
                Map.of(SIGNATURE_HEADER, signature));
        return unwrap(response.body());
    }

    private static JsonNode unwrap(JsonNode body) {
        if (body != null && body.path("data").isObject()) {
            return body.path("data");
        }
        return body;
    }

    private static String formatNumber(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        return node.path(field).asText("");
    }

    private static long integer(JsonNode node, String field) {
        if (node == null) {
            return 0L;
        }
        JsonNode value = node.path(field);
        if (value.isNumber()) {
            return value.asLong(0L);
        }
        try {
            return Long.parseLong(value.asText("0"));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requirePositive(double value, String name) {
        if (value <= 0.0d) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNonNegative(double value, String name) {
        if (value < 0.0d) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
