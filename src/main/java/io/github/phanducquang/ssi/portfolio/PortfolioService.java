package io.github.phanducquang.ssi.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.phanducquang.ssi.config.SsiConfig;
import io.github.phanducquang.ssi.portfolio.model.DerivativeAccountBalance;
import io.github.phanducquang.ssi.portfolio.model.DerivativePosition;
import io.github.phanducquang.ssi.portfolio.model.DerivativePositions;
import io.github.phanducquang.ssi.portfolio.model.DerivativePpmmr;
import io.github.phanducquang.ssi.portfolio.model.EquityAccountBalance;
import io.github.phanducquang.ssi.portfolio.model.EquityPosition;
import io.github.phanducquang.ssi.portfolio.model.EquityPpmmr;
import io.github.phanducquang.ssi.portfolio.model.Order;
import io.github.phanducquang.ssi.trading.enums.OrderSide;
import io.github.phanducquang.ssi.trading.enums.OrderStatus;
import io.github.phanducquang.ssi.trading.enums.OrderType;
import io.github.phanducquang.ssi.transport.RestTransport;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PortfolioService {
    public static final String ACCOUNT_BALANCE_PATH = "/api/v3/trading/accountBalance";
    public static final String PPMMR_PATH = "/api/v3/trading/ppmmrAccount";
    public static final String POSITION_PATH = "/api/v3/trading/position";
    public static final String ORDER_BOOK_PATH = "/api/v3/trading/orderBook";

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 1000;
    private static final ZoneId SSI_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final RestTransport restClient;
    private final String clientId;

    public PortfolioService(RestTransport restClient, SsiConfig config) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.clientId = Objects.requireNonNull(config, "config").clientId();
    }

    public EquityAccountBalance getEquityBalance(String accountNo) {
        requireText(accountNo, "accountNo");
        JsonNode payload = getBalance(accountNo);
        JsonNode equity = payload.path("equity");
        return equity.isObject() ? parseEquityBalance(equity) : null;
    }

    public DerivativeAccountBalance getDerivativeBalance(String accountNo) {
        requireText(accountNo, "accountNo");
        JsonNode payload = getBalance(accountNo);
        JsonNode derivative = payload.path("derivative");
        return derivative.isObject() ? parseDerivativeBalance(derivative) : null;
    }

    public List<Order> getTodayOrders(String accountNo) {
        requireText(accountNo, "accountNo");
        String today = LocalDate.now(SSI_ZONE).format(DATE_FORMAT);
        return getOrders(accountNo, today, today);
    }

    public List<Order> getHistoricalOrders(String accountNo, String fromDate, String toDate) {
        requireText(accountNo, "accountNo");
        requireText(fromDate, "fromDate");
        requireText(toDate, "toDate");
        return getOrders(accountNo, fromDate, toDate);
    }

    public List<EquityPosition> getEquityPositions(String accountNo) {
        requireText(accountNo, "accountNo");
        JsonNode payload = getPositions(accountNo);
        JsonNode equity = payload.path("equity");
        return equity.isArray() ? parseEquityPositions(equity) : List.of();
    }

    public DerivativePositions getDerivativePositions(String accountNo) {
        requireText(accountNo, "accountNo");
        JsonNode payload = getPositions(accountNo);
        JsonNode derivative = payload.path("derivative");
        return derivative.isObject()
                ? parseDerivativePositions(derivative)
                : new DerivativePositions(List.of(), List.of());
    }

    public List<DerivativePosition> getOpenDerivativePositions(String accountNo) {
        return getDerivativePositions(accountNo).openPositions();
    }

    public List<DerivativePosition> getClosedDerivativePositions(String accountNo) {
        return getDerivativePositions(accountNo).closedPositions();
    }

    public EquityPpmmr getEquityPpmmr(String accountNo) {
        requireText(accountNo, "accountNo");
        JsonNode payload = getPpmmr(accountNo);
        JsonNode equity = payload.path("equity");
        return equity.isObject() ? parseEquityPpmmr(equity) : null;
    }

    public DerivativePpmmr getDerivativePpmmr(String accountNo) {
        requireText(accountNo, "accountNo");
        JsonNode payload = getPpmmr(accountNo);
        JsonNode derivative = payload.path("derivative");
        return derivative.isObject() ? parseDerivativePpmmr(derivative) : null;
    }

    private JsonNode getBalance(String accountNo) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("clientId", clientId);
        params.put("accountNo", accountNo);
        return unwrap(restClient.get(ACCOUNT_BALANCE_PATH, params).body());
    }

    private List<Order> getOrders(String accountNo, String fromDate, String toDate) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("accountNo", accountNo);
        params.put("from", fromDate);
        params.put("to", toDate);
        params.put("pageIndex", DEFAULT_PAGE);
        params.put("pageSize", DEFAULT_SIZE);

        JsonNode payload = unwrap(restClient.get(ORDER_BOOK_PATH, params).body());
        String responseAccountNo = text(payload, "accountNo");
        JsonNode orderList = payload.path("orderList");
        if (!orderList.isArray()) {
            return List.of();
        }

        List<Order> result = new ArrayList<>();
        for (JsonNode item : orderList) {
            result.add(parseOrder(item, responseAccountNo.isBlank() ? accountNo : responseAccountNo));
        }
        return List.copyOf(result);
    }

    private JsonNode getPositions(String accountNo) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("clientId", clientId);
        params.put("accountNo", accountNo);
        return unwrap(restClient.get(POSITION_PATH, params).body());
    }

    private JsonNode getPpmmr(String accountNo) {
        return unwrap(restClient.get(PPMMR_PATH, Map.of("accountNo", accountNo)).body());
    }

    private static EquityAccountBalance parseEquityBalance(JsonNode data) {
        return new EquityAccountBalance(
                text(data, "accountNo"),
                number(data, "accountBalance"),
                number(data, "totalDebt"),
                number(data, "interestLoan"),
                number(data, "overdueFeeLoan"),
                number(data, "withdrawable"),
                number(data, "onHoldCash"),
                number(data, "sellUnmatched"),
                number(data, "sellT0"),
                number(data, "sellT1"),
                number(data, "sellT2"),
                number(data, "buyUnmatched"),
                number(data, "buyT0"),
                number(data, "buyT1"),
                number(data, "buyT2"),
                number(data, "advanceCashT0"),
                number(data, "advanceCashT1"),
                number(data, "holdSubscription"),
                number(data, "dividend"));
    }

    private static DerivativeAccountBalance parseDerivativeBalance(JsonNode data) {
        return new DerivativeAccountBalance(
                text(data, "accountNo"),
                number(data, "accountBalance"),
                number(data, "fee"),
                number(data, "commission"),
                number(data, "interest"),
                number(data, "extInterest"),
                number(data, "loan"),
                number(data, "deliveryAmount"),
                number(data, "floatingPL"),
                number(data, "tradingPL"),
                number(data, "totalPL"),
                number(data, "withdrawable"),
                number(data, "cashSSI"),
                number(data, "validNonCashSSI"),
                number(data, "cashWithdrawableSSI"),
                number(data, "cashVSDC"),
                number(data, "validNonCashVSDC"),
                number(data, "cashWithdrawableVSDC"));
    }

    private static List<EquityPosition> parseEquityPositions(JsonNode items) {
        List<EquityPosition> result = new ArrayList<>();
        for (JsonNode item : items) {
            result.add(new EquityPosition(
                    text(item, "accountNo"),
                    text(item, "symbol"),
                    integer(item, "quantity"),
                    integer(item, "blockQuantity"),
                    integer(item, "dividendQuantity"),
                    integer(item, "buyingQuantity"),
                    integer(item, "boughtQuantity"),
                    integer(item, "sellingQuantity"),
                    integer(item, "soldQuantity"),
                    integer(item, "t1SellQuantity"),
                    integer(item, "t2SellQuantity"),
                    number(item, "costPrice"),
                    integer(item, "mortgageQuantity"),
                    integer(item, "sellableQuantity"),
                    integer(item, "restrictedQuantity")));
        }
        return List.copyOf(result);
    }

    private static DerivativePositions parseDerivativePositions(JsonNode data) {
        return new DerivativePositions(
                parseDerivativePositionList(data.path("derOpenPositions")),
                parseDerivativePositionList(data.path("derClosePositions")));
    }

    private static List<DerivativePosition> parseDerivativePositionList(JsonNode items) {
        if (!items.isArray()) {
            return List.of();
        }
        List<DerivativePosition> result = new ArrayList<>();
        for (JsonNode item : items) {
            result.add(new DerivativePosition(
                    text(item, "accountNo"),
                    text(item, "symbol"),
                    integer(item, "long"),
                    integer(item, "short"),
                    integer(item, "net"),
                    number(item, "bidAvgPrice"),
                    number(item, "askAvgPrice"),
                    number(item, "tradePrice"),
                    number(item, "floatingPL"),
                    number(item, "tradingPL")));
        }
        return List.copyOf(result);
    }

    private static Order parseOrder(JsonNode data, String accountNo) {
        return new Order(
                accountNo,
                text(data, "clientRequestId"),
                text(data, "orderId"),
                text(data, "symbol"),
                OrderSide.fromValue(text(data, "side")),
                OrderType.fromValue(text(data, "orderType")),
                price(data.path("price")),
                number(data, "avgPrice"),
                integer(data, "quantity"),
                integer(data, "osQuantity"),
                integer(data, "filledQuantity"),
                integer(data, "cancelQuantity"),
                OrderStatus.fromValue(text(data, "orderStatus")),
                text(data, "inputTime"),
                text(data, "modifiedTime"),
                text(data, "message"));
    }

    private static EquityPpmmr parseEquityPpmmr(JsonNode data) {
        return new EquityPpmmr(
                text(data, "accountNo"),
                number(data, "dividend"),
                number(data, "loanValue"),
                number(data, "totalDebt"),
                number(data, "debt"),
                number(data, "liability"),
                number(data, "liabilitySSI"),
                number(data, "netLiability"),
                number(data, "fees"),
                number(data, "interestSSI"),
                number(data, "interestSPV"),
                number(data, "withdrawable"),
                number(data, "ee"),
                number(data, "ee50"),
                number(data, "ee60"),
                number(data, "ee70"),
                number(data, "ee80"),
                number(data, "ee90"),
                number(data, "action"),
                number(data, "actionSSI"),
                number(data, "equity"),
                number(data, "equitySSI"),
                number(data, "eeCash"),
                number(data, "holdSubscription"),
                number(data, "bankBalance"),
                number(data, "onHoldCash"),
                number(data, "doverdue"),
                number(data, "doverdueSSI"),
                number(data, "accountBalance"),
                number(data, "D"),
                number(data, "dSPV"),
                number(data, "dSSI"),
                number(data, "cia"),
                number(data, "collateralAsset"),
                number(data, "collateralAssetSSI"),
                number(data, "totalAssets"),
                number(data, "totalEquity"),
                number(data, "totalEquitySSI"),
                number(data, "lmv"),
                number(data, "lmvMargin"),
                number(data, "lmvMarginSSI"),
                number(data, "callLmv"),
                number(data, "forceLmv"),
                number(data, "callLmvSSI"),
                number(data, "forceLmvSSI"),
                number(data, "lmvNonMarginable"),
                number(data, "lmvNonMarginableSSI"),
                number(data, "preLoan"),
                number(data, "marginRatio"),
                number(data, "marginRatioSSI"),
                number(data, "purchasingPower"),
                number(data, "eeOrigin"),
                number(data, "buyUnmatched"),
                number(data, "sellUnmatched"),
                number(data, "buyT0"),
                number(data, "sellT0"),
                number(data, "sellT1"),
                number(data, "sellT2"),
                number(data, "buyT1"),
                number(data, "buyT2"),
                number(data, "creditLimit"),
                number(data, "marginCallLmvSold"),
                number(data, "marginCallLmvSoldSSI"),
                number(data, "marginCall"),
                number(data, "marginCallSSI"),
                number(data, "collateralA"),
                number(data, "collateralNon"),
                number(data, "collateralASSI"),
                number(data, "collateralNonSSI"),
                number(data, "callMargin"),
                number(data, "callForceSell"),
                number(data, "callMarginSSI"),
                number(data, "callForceSellSSI"),
                number(data, "ar"));
    }

    private static DerivativePpmmr parseDerivativePpmmr(JsonNode data) {
        return new DerivativePpmmr(
                text(data, "accountNo"),
                number(data, "accountBalance"),
                number(data, "fee"),
                number(data, "commission"),
                number(data, "interest"),
                number(data, "loan"),
                number(data, "deliveryAmount"),
                number(data, "floatingPL"),
                number(data, "tradingPL"),
                number(data, "totalPL"),
                number(data, "marginable"),
                number(data, "depositable"),
                number(data, "rcCall"),
                number(data, "withdrawable"),
                number(data, "nonCashDrawableRcCall"),
                number(data, "cashSSI"),
                number(data, "validNonCashSSI"),
                number(data, "totalAssetSSI"),
                number(data, "withdrawableSSI"),
                number(data, "eeSSI"),
                number(data, "cashVSDC"),
                number(data, "validNonCashVSDC"),
                number(data, "totalAssetVSDC"),
                number(data, "withdrawableVSDC"),
                number(data, "eeVSDC"),
                number(data, "spreadMarginSSI"),
                number(data, "deliveryMarginSSI"),
                number(data, "marginReqSSI"),
                number(data, "accountRatioSSI"),
                number(data, "usedLimitWarningLevel1SSI"),
                number(data, "usedLimitWarningLevel2SSI"),
                number(data, "usedLimitWarningLevel3SSI"),
                number(data, "marginCallSSI"),
                number(data, "spreadMarginVSDC"),
                number(data, "deliveryMarginVSDC"),
                number(data, "marginReqVSDC"),
                number(data, "accountRatioVSDC"),
                number(data, "usedLimitWarningLevel1VSDC"),
                number(data, "usedLimitWarningLevel2VSDC"),
                number(data, "usedLimitWarningLevel3VSDC"),
                number(data, "marginCallVSDC"),
                number(data, "totalEquity"),
                number(data, "extInterest"));
    }

    private static JsonNode unwrap(JsonNode body) {
        if (body != null && body.path("data").isObject()) {
            return body.path("data");
        }
        return body;
    }

    private static Object price(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0L;
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            double value = node.asDouble();
            if (value == Math.rint(value)) {
                return (long) value;
            }
            return value;
        }

        String raw = node.asText("");
        try {
            double value = Double.parseDouble(raw);
            if (value == Math.rint(value)) {
                return (long) value;
            }
            return value;
        } catch (NumberFormatException ignored) {
            OrderSide side = OrderSide.fromValue(raw);
            return side == null ? 0L : side;
        }
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("");
    }

    private static long integer(JsonNode node, String field) {
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

    private static double number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isNumber()) {
            return value.asDouble(0.0d);
        }
        try {
            return Double.parseDouble(value.asText("0"));
        } catch (NumberFormatException ignored) {
            return 0.0d;
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
