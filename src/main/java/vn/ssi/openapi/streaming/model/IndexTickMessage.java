package vn.ssi.openapi.streaming.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.math.BigDecimal;

/**
 * Index-value tick on DATA topic trade.index.&lt;CODE&gt; (e.g. VN30).
 * Nullable numeric fields preserve the distinction between missing and zero values.
 * The original payload remains available for fields not yet documented by SSI.
 */
public record IndexTickMessage(
        String symbol,
        String board,
        String status,
        String tradingTime,
        BigDecimal value,
        BigDecimal change,
        BigDecimal changePercent,
        BigDecimal advances,
        BigDecimal declines,
        BigDecimal noChange,
        BigDecimal ceilings,
        BigDecimal floors,
        BigDecimal totalMatchValue,
        BigDecimal totalMatchQuantity,
        BigDecimal totalPutValue,
        BigDecimal totalPutQuantity,
        BigDecimal totalQuantity,
        BigDecimal totalValue,
        JsonNode raw) {

    public static IndexTickMessage fromJson(JsonNode data) {
        JsonNode d = data != null && data.isObject() ? data : NullNode.getInstance();
        return new IndexTickMessage(
                text(d, "s"), text(d, "b"), text(d, "ss"), text(d, "t"),
                decimal(d, "v"), decimal(d, "c"), decimal(d, "cr"),
                decimal(d, "adv"), decimal(d, "dec"), decimal(d, "nc"),
                decimal(d, "ce"), decimal(d, "fl"), decimal(d, "tmv"),
                decimal(d, "tmq"), decimal(d, "tvp"), decimal(d, "tqp"),
                decimal(d, "tq"), decimal(d, "tv"), d.deepCopy());
    }

    private static String text(JsonNode node, String key) {
        return node.path(key).asText("");
    }

    private static BigDecimal decimal(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isNumber() && !value.isTextual()) return null;
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
