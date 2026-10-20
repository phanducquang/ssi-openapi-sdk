package vn.ssi.openapi.streaming.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * Index summary event from DATA topic indexsummary.&lt;CODE&gt;.
 * SSI has not yet published a stable field-by-field payload schema; keep the raw JSON.
 * This topic requires the stream:DATA:indexsummary scope on the API key.
 */
public record IndexSummaryMessage(String index, JsonNode raw) {
    public static IndexSummaryMessage fromJson(String topic, JsonNode data) {
        String code = topic == null || !topic.regionMatches(true, 0, "indexsummary.", 0, 13)
                ? "" : topic.substring("indexsummary.".length());
        JsonNode payload = data != null && data.isObject() ? data.deepCopy() : NullNode.getInstance();
        return new IndexSummaryMessage(code, payload);
    }
}
