package io.github.phanducquang.ssi.trading.fco;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.phanducquang.ssi.config.SsiConfig;
import io.github.phanducquang.ssi.exception.SsiApiException;
import io.github.phanducquang.ssi.trading.RequestSigner;
import io.github.phanducquang.ssi.trading.TradingService;
import io.github.phanducquang.ssi.trading.enums.OrderSide;
import io.github.phanducquang.ssi.trading.enums.OrderStatus;
import io.github.phanducquang.ssi.trading.enums.OrderType;
import io.github.phanducquang.ssi.transport.RestClient;
import io.github.phanducquang.ssi.transport.RestTransport;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class FcoService {
    public static final String FCO_ORDER_PATH = "/api/v3/trading/fco/order";
    public static final String FCO_LIST_PATH = "/api/v3/trading/fco/list";
    public static final String FCO_ORDER_BOOK_PATH = "/api/v3/trading/fco/orderbook";

    private static final String DEFAULT_DEVICE_ID = "A1:B2:C3:D4:E5:F6";
    private static final String USER_AGENT = "SSI Java SDK/0.1.0-SNAPSHOT";

    private final RestTransport restClient;
    private final ObjectMapper objectMapper;
    private final String privateKey;

    public FcoService(RestTransport restClient, SsiConfig config, ObjectMapper objectMapper) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.privateKey = Objects.requireNonNull(config, "config").privateKey();
    }

    public FcoListResponse getFcoByAccountNo(String accountNo) {
        return getFcoByAccountNo(accountNo, 1, 10);
    }

    public FcoListResponse getFcoByAccountNo(String accountNo, int pageIndex, int pageSize) {
        return getFcoList(accountNo, null, null, null, null, null, null, null, pageIndex, pageSize);
    }

    public FcoListResponse getFcoBySymbol(String accountNo, String symbol, int pageIndex, int pageSize) {
        requireText(symbol, "symbol");
        return getFcoList(accountNo, null, null, null, symbol, null, null, null, pageIndex, pageSize);
    }

    public FcoListResponse getFcoByStatus(String accountNo, String processStatus, int pageIndex, int pageSize) {
        requireText(processStatus, "processStatus");
        return getFcoList(accountNo, null, null, processStatus, null, null, null, null, pageIndex, pageSize);
    }

    public FcoListResponse getFcoByDate(
            String accountNo,
            String fromDate,
            String toDate,
            int pageIndex,
            int pageSize) {
        requireText(fromDate, "fromDate");
        requireText(toDate, "toDate");
        return getFcoList(accountNo, null, null, null, null, null, fromDate, toDate, pageIndex, pageSize);
    }

    public FcoInfo getFcoById(String accountNo, String fcoId) {
        requireText(fcoId, "fcoId");
        FcoListResponse response =
                getFcoList(accountNo, fcoId, null, null, null, null, null, null, 1, 10);
        return response.fcoList().isEmpty() ? null : response.fcoList().get(0);
    }

    public FcoOrderBookResponse getFcoOrderBook(String fcoId) {
        return getFcoOrderBook(fcoId, 1, 10);
    }

    public FcoOrderBookResponse getFcoOrderBook(String fcoId, int pageIndex, int pageSize) {
        requireText(fcoId, "fcoId");
        requirePositive(pageIndex, "pageIndex");
        requirePositive(pageSize, "pageSize");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fcoId", fcoId);
        params.put("pageIndex", pageIndex);
        params.put("pageSize", pageSize);

        return parseOrderBook(restClient.get(FCO_ORDER_BOOK_PATH, params).body());
    }

    public FcoPlaceResponse placeFcoGtd(
            String accountNo,
            String symbol,
            OrderSide side,
            long quantity,
            FcoPrice price,
            double priceSlip,
            String fromDate,
            String toDate) {
        validateCommon(accountNo, symbol, side, quantity);
        Objects.requireNonNull(price, "price");
        requireText(fromDate, "fromDate");
        requireText(toDate, "toDate");

        Map<String, Object> body = commonBody(accountNo, FcoType.GTD, symbol, side, quantity, fromDate, toDate);
        body.put("price", price.value());
        body.put("priceSlip", price.effectiveSlip(priceSlip));
        return place(body);
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
        validateCommon(accountNo, symbol, side, quantity);
        requireNonNegative(stopPrice, "stopPrice");
        Objects.requireNonNull(operator, "operator");

        Map<String, Object> body = commonBody(accountNo, FcoType.STOP, symbol, side, quantity, fromDate, toDate);
        body.put("price", OrderType.MTL.value());
        body.put("priceSlip", 0);
        body.put("stopPrice", numberValue(stopPrice));
        body.put("operator", operator.value());
        return place(body);
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
        validateCommon(accountNo, symbol, side, quantity);
        requireNonNegative(price, "price");
        requireNonNegative(stopPrice, "stopPrice");
        Objects.requireNonNull(operator, "operator");

        Map<String, Object> body = commonBody(accountNo, FcoType.STOP_LIMIT, symbol, side, quantity, fromDate, toDate);
        body.put("price", formatNumber(price));
        body.put("priceSlip", numberValue(priceSlip));
        body.put("stopPrice", numberValue(stopPrice));
        body.put("operator", operator.value());
        return place(body);
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
        validateCommon(accountNo, symbol, side, quantity);

        Map<String, Object> body = commonBody(accountNo, FcoType.TRAILING_STOP, symbol, side, quantity, fromDate, toDate);
        body.put("activePrice", numberValue(activePrice));
        body.put("trailingAmount", numberValue(trailingAmount));
        body.put("price", OrderType.MTL.value());
        body.put("priceSlip", 0);
        return place(body);
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
        validateCommon(accountNo, symbol, side, quantity);

        Map<String, Object> body = commonBody(accountNo, FcoType.TRAILING_STOP_LIMIT, symbol, side, quantity, fromDate, toDate);
        body.put("activePrice", numberValue(activePrice));
        body.put("trailingAmount", numberValue(trailingAmount));
        body.put("priceSlip", numberValue(priceSlip));
        return place(body);
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
        validateCommon(accountNo, symbol, side, quantity);
        Objects.requireNonNull(tpPrice, "tpPrice");
        Objects.requireNonNull(slPrice, "slPrice");

        Map<String, Object> body = commonBody(accountNo, FcoType.OCO, symbol, side, quantity, fromDate, toDate);
        body.put("tpActivePrice", numberValue(tpActivePrice));
        body.put("slActivePrice", numberValue(slActivePrice));
        body.put("tpPrice", tpPrice.value());
        body.put("slPrice", slPrice.value());
        body.put("tpSlip", numberValue(tpPrice.effectiveSlip(tpSlip)));
        body.put("slSlip", numberValue(slPrice.effectiveSlip(slSlip)));

        // Mirrors the upstream Python payload.
        body.put("price", "MP");
        body.put("priceSlip", 0);
        body.put("stopPrice", 0);
        body.put("activePrice", 0);
        body.put("trailingAmount", 0);
        body.put("operator", "");
        body.put("code", "");
        return place(body);
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
        validateCommon(accountNo, symbol, side, quantity);
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(tpPrice, "tpPrice");
        Objects.requireNonNull(slPrice, "slPrice");

        Map<String, Object> body = commonBody(accountNo, FcoType.BULL_BEAR, symbol, side, quantity, fromDate, toDate);
        body.put("price", price.value());
        body.put("priceSlip", numberValue(price.effectiveSlip(priceSlip)));
        body.put("tpActivePrice", numberValue(tpActivePrice));
        body.put("slActivePrice", numberValue(slActivePrice));
        body.put("tpPrice", tpPrice.value());
        body.put("slPrice", slPrice.value());
        body.put("tpSlip", numberValue(tpPrice.effectiveSlip(tpSlip)));
        body.put("slSlip", numberValue(slPrice.effectiveSlip(slSlip)));
        return place(body);
    }

    public FcoCancelResponse cancelFco(String fcoId) {
        requireText(fcoId, "fcoId");
        JsonNode response = signedRequest("DELETE", FCO_ORDER_PATH, Map.of("fcoId", fcoId));
        return new FcoCancelResponse(text(response, "fcoId"));
    }

    private FcoListResponse getFcoList(
            String accountNo,
            String fcoId,
            FcoType type,
            String processStatus,
            String symbol,
            OrderSide side,
            String fromDate,
            String toDate,
            int pageIndex,
            int pageSize) {
        requireText(accountNo, "accountNo");
        requirePositive(pageIndex, "pageIndex");
        requirePositive(pageSize, "pageSize");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("accountNo", accountNo);
        if (fcoId != null) params.put("fcoId", fcoId);
        if (type != null) params.put("type", type.value());
        if (processStatus != null) params.put("processStatus", processStatus);
        if (symbol != null) params.put("symbol", symbol);
        if (side != null) params.put("side", side.value());
        if (fromDate != null) params.put("from", fromDate);
        if (toDate != null) params.put("to", toDate);
        params.put("pageIndex", pageIndex);
        params.put("pageSize", pageSize);

        return parseList(restClient.get(FCO_LIST_PATH, params).body());
    }

    private FcoPlaceResponse place(Map<String, Object> body) {
        JsonNode response = signedRequest("POST", FCO_ORDER_PATH, body);
        return new FcoPlaceResponse(text(response, "fcoId"));
    }

    private JsonNode signedRequest(String method, String path, Map<String, Object> body) {
        String rawBody;
        try {
            rawBody = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new SsiApiException("Failed to serialize SSI FCO request", e);
        }

        String signature = RequestSigner.sign(rawBody, privateKey);
        RestClient.ApiResponse response = restClient.request(
                method,
                path,
                Map.of(),
                rawBody,
                Map.of(TradingService.SIGNATURE_HEADER, signature));
        return unwrapObject(response.body());
    }

    private static Map<String, Object> commonBody(
            String accountNo,
            FcoType type,
            String symbol,
            OrderSide side,
            long quantity,
            String fromDate,
            String toDate) {
        requireText(fromDate, "fromDate");
        requireText(toDate, "toDate");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountNo", accountNo);
        body.put("type", type.value());
        body.put("symbol", symbol);
        body.put("side", side.value());
        body.put("quantity", quantity);
        body.put("from", fromDate);
        body.put("to", toDate);
        body.put("deviceId", DEFAULT_DEVICE_ID);
        body.put("userAgent", USER_AGENT);
        return body;
    }

    private static FcoListResponse parseList(JsonNode body) {
        if (body == null) return new FcoListResponse(1, 10, 0, 0, List.of());

        JsonNode items = body.isArray() ? body : firstArray(body, "data", "fcoList", "fco_list");
        List<FcoInfo> result = new ArrayList<>();
        if (items != null && items.isArray()) {
            for (JsonNode item : items) result.add(parseInfo(item));
        }

        return new FcoListResponse(
                body.isObject() ? integer(body, "pageIndex", 1) : 1,
                body.isObject() ? integer(body, "pageSize", 10) : 10,
                body.isObject() ? integer(body, "itemsCount", 0) : 0,
                body.isObject() ? integer(body, "pagesCount", 0) : 0,
                result);
    }

    private static FcoOrderBookResponse parseOrderBook(JsonNode body) {
        if (body == null) return new FcoOrderBookResponse(1, 10, 0, 0, List.of());

        JsonNode items = body.isArray() ? body : firstArray(body, "data", "orderBook", "order_book");
        List<FcoOrder> result = new ArrayList<>();
        if (items != null && items.isArray()) {
            for (JsonNode item : items) result.add(parseOrder(item));
        }

        return new FcoOrderBookResponse(
                body.isObject() ? integer(body, "pageIndex", 1) : 1,
                body.isObject() ? integer(body, "pageSize", 10) : 10,
                body.isObject() ? integer(body, "itemsCount", 0) : 0,
                body.isObject() ? integer(body, "pagesCount", 0) : 0,
                result);
    }

    private static FcoInfo parseInfo(JsonNode data) {
        JsonNode paramsNode = firstObject(data, "params", "fco_params", "fcoParams");
        return new FcoInfo(
                text(data, "fcoId"),
                text(data, "username"),
                text(data, "accountNo"),
                longValue(data, "quantity"),
                scalarText(data.path("price")),
                scalarText(data.path("priceSlip")),
                text(data, "symbol"),
                FcoType.fromValue(text(data, "type")),
                text(data, "from"),
                text(data, "to"),
                longValue(data, "matchedQuantity"),
                data.path("isPlaceOrder").asBoolean(false),
                FcoStatus.fromValue(text(data, "status")),
                text(data, "detail"),
                paramsNode == null ? null : parseParams(paramsNode));
    }

    private static FcoParams parseParams(JsonNode data) {
        return new FcoParams(
                nullableDouble(data, "stopPrice"),
                OrderSide.fromValue(text(data, "side")),
                nullableDouble(data, "activePrice"),
                nullableDouble(data, "trailingAmount"),
                nullableDouble(data, "tpActivePrice"),
                nullableDouble(data, "slActivePrice"),
                nullableScalarText(data.path("tpPrice")),
                nullableScalarText(data.path("slPrice")),
                nullableDouble(data, "tpSlip"),
                nullableDouble(data, "slSlip"),
                FcoOperator.fromValue(text(data, "operator")));
    }

    private static FcoOrder parseOrder(JsonNode data) {
        return new FcoOrder(
                text(data, "fcoId"),
                text(data, "accountNo"),
                doubleValue(data, "quantity"),
                scalarText(data.path("price")),
                text(data, "symbol"),
                OrderSide.fromValue(text(data, "side")),
                OrderType.fromValue(text(data, "orderType")),
                data.path("isMainOrder").asBoolean(false),
                data.path("isAttachedOrder").asBoolean(false),
                text(data, "createdTime"),
                text(data, "updatedTime"),
                text(data, "uniqueId"),
                text(data, "orderId"),
                doubleValue(data, "matchedQuantity"),
                doubleValue(data, "osQuantity"),
                doubleValue(data, "avgPrice"),
                OrderStatus.fromValue(text(data, "status")),
                text(data, "detail"));
    }

    private static JsonNode unwrapObject(JsonNode body) {
        if (body != null && body.path("data").isObject()) return body.path("data");
        return body;
    }

    private static JsonNode firstArray(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isArray()) return value;
        }
        return null;
    }

    private static JsonNode firstObject(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isObject()) return value;
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("");
    }

    private static String scalarText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return "";
        return node.asText("");
    }

    private static String nullableScalarText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        return node.asText();
    }

    private static int integer(JsonNode node, String field, int defaultValue) {
        JsonNode value = node.path(field);
        if (value.isNumber()) return value.asInt(defaultValue);
        try {
            return Integer.parseInt(value.asText(String.valueOf(defaultValue)));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static long longValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isNumber()) return value.asLong();
        try {
            return new BigDecimal(value.asText("0")).longValue();
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static double doubleValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isNumber()) return value.asDouble();
        try {
            return Double.parseDouble(value.asText("0"));
        } catch (NumberFormatException ignored) {
            return 0.0d;
        }
    }

    private static Double nullableDouble(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (value.isNumber()) return value.asDouble();
        try {
            return Double.parseDouble(value.asText());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Object numberValue(double value) {
        if (value == Math.rint(value)) return (long) value;
        return value;
    }

    private static String formatNumber(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static void validateCommon(String accountNo, String symbol, OrderSide side, long quantity) {
        requireText(accountNo, "accountNo");
        requireText(symbol, "symbol");
        Objects.requireNonNull(side, "side");
        requirePositive(quantity, "quantity");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void requireNonNegative(double value, String name) {
        if (value < 0.0d) throw new IllegalArgumentException(name + " must be non-negative");
    }
}
