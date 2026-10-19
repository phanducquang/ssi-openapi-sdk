package vn.ssi.openapi.streaming;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import vn.ssi.openapi.streaming.model.*;
import vn.ssi.openapi.trading.enums.OrderSide;
import vn.ssi.openapi.trading.enums.OrderStatus;
import vn.ssi.openapi.trading.enums.OrderType;
import vn.ssi.openapi.trading.fco.FcoStatus;
import vn.ssi.openapi.trading.fco.FcoType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class StreamingMessageDispatcher {
    private static final Logger log = LoggerFactory.getLogger(StreamingMessageDispatcher.class);
    private final ObjectMapper objectMapper;
    private final ObjectReader quoteReader;
    private final List<Consumer<JsonNode>> rawListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<TradeMessage>> tradeListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<IntervalMessage>> intervalListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<QuoteMessage>> quoteListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ForeignRoomMessage>> roomListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<MarketStatusMessage>> marketListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<PutMessage>> putListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<OddLotMessage>> oddLotListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<HeartbeatMessage>> heartbeatListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<OrderStatusMessage>> orderStatusListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<PortfolioMessage>> portfolioListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<FcoOrderUpdateMessage>> fcoOrderUpdateListeners = new CopyOnWriteArrayList<>();

    public StreamingMessageDispatcher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.quoteReader = objectMapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    public void dispatch(String rawMessage) {
        final JsonNode message;
        try { message = objectMapper.readTree(rawMessage); }
        catch (JsonProcessingException e) { log.warn("Ignoring non-JSON SSI streaming message: {}", rawMessage); return; }
        if (message == null) return;
        notifyListeners(rawListeners, message, "raw");
        String channel = message.path("channel").asText("");
        if ("HEARTBEAT".equals(channel)) {
            notifyListeners(heartbeatListeners, new HeartbeatMessage(message.path("method").asText(""), channel, message.path("status").asText(""), message.path("message").asText("")), "heartbeat");
            return;
        }
        String topic = message.path("topic").asText("");
        JsonNode data = message.path("data");

        if ("TRADING".equals(channel)) {
            if (topic.startsWith("order.")) {
                if ("fcoEvent".equals(data.path("eventType").asText(""))) {
                    notifyListeners(fcoOrderUpdateListeners, parseFcoOrderUpdate(data), "fco-order-update");
                } else {
                    notifyListeners(orderStatusListeners, parseOrderStatus(data), "order-status");
                }
            } else if (topic.startsWith("portfolio.")) {
                notifyListeners(portfolioListeners, parsePortfolio(data), "portfolio");
            }
            return;
        }

        if (!"DATA".equals(channel)) return;
        if (topic.startsWith("trade.")) {
            if (topic.contains("@")) notifyListeners(intervalListeners, parseInterval(data), "interval");
            else notifyListeners(tradeListeners, parseTrade(data), "trade");
        } else if (topic.startsWith("quote.")) {
            final QuoteMessage quote;
            try {
                JsonNode quoteEnvelope = quoteReader.readTree(rawMessage);
                quote = parseQuote(quoteEnvelope.path("data"));
            } catch (JsonProcessingException | IllegalArgumentException e) {
                log.warn("Ignoring invalid SSI Quote message for topic {}: {}", topic, e.getMessage());
                return;
            }
            notifyListeners(quoteListeners, quote, "quote");
        }
        else if (topic.startsWith("room.")) notifyListeners(roomListeners, parseRoom(data), "room");
        else if (topic.startsWith("market.")) notifyListeners(marketListeners, parseMarket(data), "market");
        else if (topic.startsWith("put.")) notifyListeners(putListeners, parsePut(data), "put");
        else if (topic.startsWith("oddlot.")) notifyListeners(oddLotListeners, parseOddLot(data), "oddlot");
    }

    public StreamingMessageDispatcher onRaw(Consumer<JsonNode> listener) { rawListeners.add(listener); return this; }
    public StreamingMessageDispatcher onTrade(Consumer<TradeMessage> listener) { tradeListeners.add(listener); return this; }
    public StreamingMessageDispatcher onInterval(Consumer<IntervalMessage> listener) { intervalListeners.add(listener); return this; }
    public StreamingMessageDispatcher onQuote(Consumer<QuoteMessage> listener) { quoteListeners.add(listener); return this; }
    public StreamingMessageDispatcher onForeignRoom(Consumer<ForeignRoomMessage> listener) { roomListeners.add(listener); return this; }
    public StreamingMessageDispatcher onMarketStatus(Consumer<MarketStatusMessage> listener) { marketListeners.add(listener); return this; }
    public StreamingMessageDispatcher onPutThrough(Consumer<PutMessage> listener) { putListeners.add(listener); return this; }
    public StreamingMessageDispatcher onOddLot(Consumer<OddLotMessage> listener) { oddLotListeners.add(listener); return this; }
    public StreamingMessageDispatcher onHeartbeat(Consumer<HeartbeatMessage> listener) { heartbeatListeners.add(listener); return this; }
    public StreamingMessageDispatcher onOrderStatus(Consumer<OrderStatusMessage> listener) { orderStatusListeners.add(listener); return this; }
    public StreamingMessageDispatcher onPortfolio(Consumer<PortfolioMessage> listener) { portfolioListeners.add(listener); return this; }
    public StreamingMessageDispatcher onFcoOrderUpdate(Consumer<FcoOrderUpdateMessage> listener) { fcoOrderUpdateListeners.add(listener); return this; }

    private TradeMessage parseTrade(JsonNode d) {
        return new TradeMessage(
                text(d, "t"),
                text(d, "s"),
                text(d, "p"),
                text(d, "q"),
                text(d, "a"),
                defaultText(d, "si", "C"),
                text(d, "o"),
                text(d, "h"),
                text(d, "l"),
                text(d, "v")
        );
    }
    private IntervalMessage parseInterval(JsonNode d) { return new IntervalMessage(text(d,"st"), text(d,"t"), text(d,"s"), decimal(d,"o"), decimal(d,"h"), decimal(d,"l"), decimal(d,"c"), longValue(d,"v")); }
    private QuoteMessage parseQuote(JsonNode d) {
        if (!d.isObject()) throw new IllegalArgumentException("data must be an object");
        return new QuoteMessage(text(d, "t"), text(d, "s"), quoteLevels(d, "bids"), quoteLevels(d, "asks"),
                d.has("bids"), d.has("asks"));
    }
    private ForeignRoomMessage parseRoom(JsonNode d) { return new ForeignRoomMessage(text(d,"t"), text(d,"s"), text(d,"tr"), text(d,"cr"), text(d,"bq"), text(d,"bv"), text(d,"sq"), text(d,"sv")); }
    private MarketStatusMessage parseMarket(JsonNode d) { return new MarketStatusMessage(text(d,"market"), text(d,"status"), text(d,"tradingDate")); }
    private PutMessage parsePut(JsonNode d) { return new PutMessage(text(d,"t"), text(d,"s"), text(d,"p"), text(d,"q"), text(d,"tq"), text(d,"tv")); }
    private OddLotMessage parseOddLot(JsonNode d) { return new OddLotMessage(text(d,"t"), text(d,"s"), decimal(d,"p"), longValue(d,"q"), levels(d.path("bids")), levels(d.path("asks"))); }

    private OrderStatusMessage parseOrderStatus(JsonNode d) {
        return new OrderStatusMessage(
                text(d, "accountNo"),
                text(d, "clientRequestId"),
                text(d, "orderId"),
                text(d, "symbol"),
                OrderSide.fromValue(text(d, "side")),
                OrderType.fromValue(text(d, "orderType")),
                priceValue(d.path("price")),
                longValue(d, "quantity"),
                longValue(d, "osQty"),
                longValue(d, "filledQty"),
                longValue(d, "cancelQty"),
                OrderStatus.fromValue(text(d, "orderStatus")),
                text(d, "inputTime"),
                text(d, "modifyTime"),
                text(d, "rejectReason"));
    }

    private PortfolioMessage parsePortfolio(JsonNode d) {
        return new PortfolioMessage(
                text(d, "accountNo"),
                doubleValue(d, "totalAsset"),
                doubleValue(d, "cashBalance"),
                doubleValue(d, "stockValue"));
    }

    private FcoOrderUpdateMessage parseFcoOrderUpdate(JsonNode d) {
        return new FcoOrderUpdateMessage(
                text(d, "fcoId"),
                FcoStatus.fromValue(text(d, "processStatus")),
                longValue(d, "matchedQuantity"),
                d.path("isPlaceOrder").asBoolean(false),
                text(d, "symbol"),
                longValue(d, "quantity"),
                text(d, "price"),
                text(d, "accountNo"),
                text(d, "updatedTime"),
                text(d, "status"),
                text(d, "message"),
                text(d, "username"),
                text(d, "eventType"),
                FcoType.fromValue(text(d, "type")));
    }

    private List<PriceLevel> quoteLevels(JsonNode data, String side) {
        JsonNode node = data.get(side);
        if (node == null) return List.of();
        if (!node.isArray()) throw new IllegalArgumentException(side + " must be an array");
        List<PriceLevel> result = new ArrayList<>(node.size());
        for (int i = 0; i < node.size(); i++) {
            JsonNode tuple = node.get(i);
            String path = side + "[" + i + "]";
            if (!tuple.isArray() || tuple.size() != 2) {
                throw new IllegalArgumentException(path + " must be exactly [price, quantity]");
            }
            BigDecimal decimalPrice = quoteNumber(tuple.get(0), path + ".price");
            double price = decimalPrice.doubleValue();
            if (decimalPrice.signum() < 0 || !Double.isFinite(price)) {
                throw new IllegalArgumentException(path + ".price must be finite and non-negative");
            }
            BigDecimal decimalQuantity = quoteNumber(tuple.get(1), path + ".quantity");
            final long quantity;
            try {
                quantity = decimalQuantity.longValueExact();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(path + ".quantity must be an exact long", e);
            }
            if (quantity < 0) throw new IllegalArgumentException(path + ".quantity must be non-negative");
            result.add(new PriceLevel(price, quantity));
        }
        return result;
    }

    private BigDecimal quoteNumber(JsonNode node, String path) {
        if (node == null || (!node.isNumber() && !node.isTextual())) {
            throw new IllegalArgumentException(path + " must be a number or numeric string");
        }
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(path + " must be a valid decimal number", e);
        }
    }

    private List<PriceLevel> levels(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<PriceLevel> result = new ArrayList<>();
        for (JsonNode item : node) if (item.isArray() && item.size() >= 2) result.add(new PriceLevel(decimal(item.get(0)).doubleValue(), longValue(item.get(1))));
        return result;
    }
    private String text(JsonNode d, String f) { return d.path(f).asText(""); }
    private String defaultText(JsonNode d, String f, String def) { String v = text(d,f); return v.isBlank() ? def : v; }
    private long longValue(JsonNode d, String f) { return longValue(d.path(f)); }
    private long longValue(JsonNode n) { if (n == null || n.isNull()) return 0L; if (n.isNumber()) return n.longValue(); try { return new BigDecimal(n.asText("0")).longValue(); } catch (NumberFormatException e) { return 0L; } }
    private BigDecimal decimal(JsonNode d, String f) { return decimal(d.path(f)); }
    private BigDecimal decimal(JsonNode n) { if (n == null || n.isNull()) return BigDecimal.ZERO; if (n.isNumber()) return n.decimalValue(); try { return new BigDecimal(n.asText("0")); } catch (NumberFormatException e) { return BigDecimal.ZERO; } }

    private double doubleValue(JsonNode d, String f) {
        JsonNode n = d.path(f);
        if (n.isNumber()) return n.asDouble();
        try { return Double.parseDouble(n.asText("0")); }
        catch (NumberFormatException e) { return 0.0d; }
    }

    private Object priceValue(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return 0L;
        if (n.isIntegralNumber()) return n.longValue();
        if (n.isFloatingPointNumber()) {
            double value = n.doubleValue();
            if (value == Math.rint(value)) return (long) value;
            return value;
        }
        String raw = n.asText("");
        try {
            BigDecimal value = new BigDecimal(raw);
            if (value.stripTrailingZeros().scale() <= 0) return value.longValue();
            return value.doubleValue();
        } catch (NumberFormatException ignored) {
            OrderSide side = OrderSide.fromValue(raw);
            return side == null ? 0L : side;
        }
    }

    private <T> void notifyListeners(List<Consumer<T>> listeners, T message, String type) { for (Consumer<T> listener : listeners) try { listener.accept(message); } catch (RuntimeException e) { log.error("SSI {} callback failed", type, e); } }
}
