package io.github.phanducquang.ssi.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.phanducquang.ssi.streaming.model.QuoteMessage;
import io.github.phanducquang.ssi.streaming.model.TradeMessage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamingMessageDispatcherTest {
    @Test
    void dispatchesTradePayloadToTypedListener() {
        StreamingMessageDispatcher dispatcher = new StreamingMessageDispatcher(new ObjectMapper());
        AtomicReference<TradeMessage> received = new AtomicReference<>();
        dispatcher.onTrade(received::set);
        dispatcher.dispatch("{\"channel\":\"DATA\",\"topic\":\"trade.VNM\",\"data\":{\"t\":\"10:15:01\",\"s\":\"VNM\",\"p\":76500,\"q\":100,\"si\":\"B\",\"v\":120000}}");
        assertEquals("VNM", received.get().symbol());
        assertEquals(new BigDecimal("76500"), received.get().price());
        assertEquals(100L, received.get().quantity());
        assertEquals("B", received.get().side());
    }

    @Test
    void dispatchesQuoteLevels() {
        StreamingMessageDispatcher dispatcher = new StreamingMessageDispatcher(new ObjectMapper());
        AtomicReference<QuoteMessage> received = new AtomicReference<>();
        dispatcher.onQuote(received::set);
        dispatcher.dispatch("{\"channel\":\"DATA\",\"topic\":\"quote.VNM\",\"data\":{\"t\":\"10:15:02\",\"s\":\"VNM\",\"bids\":[[76400,1200],[76300,900]],\"asks\":[[76500,700]]}}");
        assertEquals(2, received.get().bids().size());
        assertEquals(new BigDecimal("76400"), received.get().bids().get(0).price());
        assertEquals(1200L, received.get().bids().get(0).volume());
        assertEquals(1, received.get().asks().size());
    }
}
