package vn.ssi.openapi.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.ssi.openapi.streaming.model.IndexTickMessage;
import vn.ssi.openapi.streaming.model.IndexSummaryMessage;
import vn.ssi.openapi.streaming.model.IntervalMessage;
import vn.ssi.openapi.streaming.model.TradeMessage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IndexStreamingMessageDispatcherTest {
    private final StreamingMessageDispatcher dispatcher = new StreamingMessageDispatcher(new ObjectMapper());

    @Test
    void routesIndexTickSeparatelyFromStockTradeAndKeepsOriginalPayload() {
        List<IndexTickMessage> ticks = new ArrayList<>();
        List<TradeMessage> trades = new ArrayList<>();
        dispatcher.onIndexTick(ticks::add).onTrade(trades::add);

        dispatcher.dispatch("""
                {"channel":"DATA","topic":"trade.index.VN30","data":{
                  "s":"VN30","b":"HOSE","ss":"ATO","t":"2026/10/20 09:15:02",
                  "v":"1450.25","c":-2.5,"cr":"-0.17","adv":10,"dec":16,"nc":4,
                  "ce":1,"fl":0,"tmv":"1234567890.01","tmq":123456,"tvp":50,
                  "tqp":60,"tq":123516,"tv":"1234567940.01","unknown":"preserved"
                }}
                """);

        assertEquals(1, ticks.size());
        assertTrue(trades.isEmpty());
        IndexTickMessage tick = ticks.get(0);
        assertEquals("VN30", tick.symbol());
        assertEquals("HOSE", tick.board());
        assertEquals(new BigDecimal("1450.25"), tick.value());
        assertEquals(new BigDecimal("-2.5"), tick.change());
        assertEquals(new BigDecimal("1234567890.01"), tick.totalMatchValue());
        assertEquals(new BigDecimal("1234567940.01"), tick.totalValue());
        assertEquals("preserved", tick.raw().path("unknown").asText());

        dispatcher.dispatch("""
                {"channel":"DATA","topic":"trade.VNM","data":{"s":"VNM","p":"72.1","q":100}}
                """);
        assertEquals(1, trades.size());
        assertEquals("VNM", trades.get(0).symbol());
    }

    @Test
    void indexIntervalUsesExistingIntervalHandlerWithoutEmittingTick() {
        List<IntervalMessage> candles = new ArrayList<>();
        List<IndexTickMessage> ticks = new ArrayList<>();
        dispatcher.onInterval(candles::add).onIndexTick(ticks::add);
        dispatcher.dispatch("""
                {"channel":"DATA","topic":"trade.index.VN30@1m",
                 "data":{"s":"VN30","st":"1m","t":"2026/10/20 09:21:00",
                 "o":"1420.1","h":"1421.1","l":"1419.8","c":"1420.8","v":500}}
                """);
        assertEquals(1, candles.size());
        assertTrue(ticks.isEmpty());
        assertEquals("VN30", candles.get(0).symbol());
        assertEquals(new BigDecimal("1420.8"), candles.get(0).close());
    }

    @Test
    void parsesIndexSummaryAsRawAndDoesNotDispatchAsStockTrade() {
        List<IndexSummaryMessage> summaries = new ArrayList<>();
        List<TradeMessage> trades = new ArrayList<>();
        dispatcher.onIndexSummary(summaries::add).onTrade(trades::add);
        dispatcher.dispatch("""
                {"channel":"DATA","topic":"indexsummary.VN30",
                "data":{"value":"1450.5","advances":11,"unknown":{"extra":true}}}
                """);
        assertEquals(1, summaries.size());
        assertTrue(trades.isEmpty());
        assertEquals("VN30", summaries.get(0).index());
        assertEquals("1450.5", summaries.get(0).raw().path("value").asText());
        assertTrue(summaries.get(0).raw().path("unknown").path("extra").asBoolean());
    }

    @Test
    void supportsMissingOptionalIndexTickFields() {
        List<IndexTickMessage> ticks = new ArrayList<>();
        dispatcher.onIndexTick(ticks::add);
        dispatcher.dispatch("""
                {"channel":"DATA","topic":"trade.index.HNXINDEX","data":{"s":"HNXINDEX","v":125.5}}
                """);
        assertEquals(1, ticks.size());
        assertNull(ticks.get(0).change());
        assertEquals(new BigDecimal("125.5"), ticks.get(0).value());
    }
}
