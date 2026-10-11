package vn.ssi.openapi.streaming;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.ssi.openapi.streaming.model.OddLotMessage;
import vn.ssi.openapi.streaming.model.PriceLevel;
import vn.ssi.openapi.streaming.model.QuoteMessage;
import vn.ssi.openapi.streaming.model.TradeMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StreamingMessageDispatcherTest {
    private final StreamingMessageDispatcher dispatcher = new StreamingMessageDispatcher(new ObjectMapper());
    private final List<QuoteMessage> quotes = new ArrayList<>();

    StreamingMessageDispatcherTest() {
        dispatcher.onQuote(quotes::add);
    }

    @Test
    void distinguishesMissingSidesFromEmptySides() {
        dispatchQuote("{}");
        dispatchQuote("{\"bids\":[],\"asks\":[]}");
        dispatchQuote("{\"bids\":[]}");
        dispatchQuote("{\"asks\":[]}");

        assertEquals(4, quotes.size());
        assertFalse(quotes.get(0).bidsPresent());
        assertFalse(quotes.get(0).asksPresent());
        assertTrue(quotes.get(1).bidsPresent());
        assertTrue(quotes.get(1).asksPresent());
        assertTrue(quotes.get(2).bidsPresent());
        assertFalse(quotes.get(2).asksPresent());
        assertFalse(quotes.get(3).bidsPresent());
        assertTrue(quotes.get(3).asksPresent());
        for (QuoteMessage quote : quotes) {
            assertTrue(quote.bids().isEmpty());
            assertTrue(quote.asks().isEmpty());
        }
    }

    @Test
    void preservesTopOrderAndDoublePrice() {
        dispatchQuote("""
                {"t":"10:15:02","s":"VNM",
                 "bids":[[45.45,100],[46,200],[44,300]],
                 "asks":[[48,400],[47.5,500],[49,600]]}
                """);

        QuoteMessage quote = quotes.get(0);
        assertEquals("10:15:02", quote.tradingTime());
        assertEquals("VNM", quote.symbol());
        assertTrue(quote.bidsPresent());
        assertTrue(quote.asksPresent());
        assertEquals(List.of(new PriceLevel(45.45d, 100), new PriceLevel(46d, 200), new PriceLevel(44d, 300)), quote.bids());
        assertEquals(List.of(new PriceLevel(48d, 400), new PriceLevel(47.5d, 500), new PriceLevel(49d, 600)), quote.asks());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "{}", "\"invalid\"", "0", "true",
            "[null]", "[{}]", "[\"invalid\"]", "[1]",
            "[[]]", "[[45.45]]", "[[45.45,100,1]]",
            "[[null,100]]", "[[true,100]]", "[[{},100]]", "[[[],100]]",
            "[[\"invalid\",100]]", "[[\"\",100]]", "[[\"NaN\",100]]",
            "[[\"Infinity\",100]]", "[[\"-Infinity\",100]]",
            "[[1e309,100]]", "[[-1,100]]", "[[-1e-400,100]]",
            "[[45.45,null]]", "[[45.45,true]]", "[[45.45,{}]]", "[[45.45,[]]]",
            "[[45.45,\"invalid\"]]", "[[45.45,\"\"]]", "[[45.45,\"NaN\"]]",
            "[[45.45,\"Infinity\"]]", "[[45.45,-1]]", "[[45.45,-0.1]]",
            "[[45.45,1.5]]", "[[45.45,\"1.5\"]]",
            "[[45.45,1.0000000000000000000001]]",
            "[[45.45,9223372036854775808]]", "[[45.45,9223372036854775808.0]]",
            "[[45.45,9223372036854775807.1]]", "[[45.45,\"9223372036854775808\"]]",
            "[[45.45,1e100]]",
            "[[45.45,100],[44,\"invalid\"]]", "[[45.45,100],[44,100,1]]"
    })
    void rejectsEntireQuoteAndContinuesAfterAnInvalidSide(String invalidSide) {
        for (String side : List.of("bids", "asks")) {
            quotes.clear();
            String otherSide = side.equals("bids") ? "asks" : "bids";
            String data = "{\"" + otherSide + "\":[[45.45,100]],\"" + side + "\":" + invalidSide + "}";

            assertDoesNotThrow(() -> dispatchQuote(data));
            assertTrue(quotes.isEmpty(), "Invalid " + side + " must reject the entire Quote");

            dispatchQuote("{\"bids\":[[45.45,100]],\"asks\":[]}");
            assertEquals(1, quotes.size(), "A rejected Quote must not block the next message");
            assertEquals(new PriceLevel(45.45d, 100), quotes.get(0).bids().get(0));
        }
    }

    @Test
    void acceptsZeroNumericStringsAndExactLongQuantities() {
        dispatchQuote("""
                {"bids":[[0,0],["45.45","100"],[45.45,1.0],[45.45,1e2]],
                 "asks":[[45.45,9223372036854775807],
                         [45.45,9223372036854775807.0],
                         [45.45,"9223372036854775807"],
                         [45.45,9007199254740993.0]]}
                """);

        assertEquals(1, quotes.size());
        assertEquals(List.of(new PriceLevel(0d, 0), new PriceLevel(45.45d, 100),
                new PriceLevel(45.45d, 1), new PriceLevel(45.45d, 100)), quotes.get(0).bids());
        assertEquals(List.of(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 9007199254740993L),
                quotes.get(0).asks().stream().map(PriceLevel::volume).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "\"invalid\"", "1", "true"})
    void rejectsNonObjectData(String data) {
        assertDoesNotThrow(() -> dispatchQuote(data));
        assertTrue(quotes.isEmpty());
    }

    @Test
    void rejectsMissingDataAndMalformedJsonWithoutBlockingValidQuote() {
        for (String message : List.of("{\"channel\":\"DATA\",\"topic\":\"quote.VNM\"}",
                "{invalid", "", "null")) {
            assertDoesNotThrow(() -> dispatcher.dispatch(message));
        }
        assertTrue(quotes.isEmpty());
        dispatchQuote("{\"bids\":[[45.45,100]]}");
        assertEquals(1, quotes.size());
    }

    @Test
    void isolatesQuoteCallbackFailures() {
        AtomicReference<QuoteMessage> received = new AtomicReference<>();
        dispatcher.onQuote(quote -> { throw new IllegalStateException("Callback failure"); });
        dispatcher.onQuote(received::set);

        assertDoesNotThrow(() -> dispatchQuote("{\"bids\":[[45.45,100]]}"));
        assertSame(quotes.get(0), received.get());
    }

    @Test
    void keepsSharedMapperAndRawNumberParsingUnchanged() {
        ObjectMapper mapper = new ObjectMapper();
        StreamingMessageDispatcher localDispatcher = new StreamingMessageDispatcher(mapper);
        AtomicReference<JsonNode> raw = new AtomicReference<>();
        List<QuoteMessage> received = new ArrayList<>();
        localDispatcher.onRaw(raw::set).onQuote(received::add);

        localDispatcher.dispatch(envelope("quote.VNM", "{\"bids\":[[45.45,9007199254740993.0]]}"));

        assertFalse(mapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS));
        assertTrue(raw.get().path("data").path("bids").get(0).get(1).isDouble());
        assertEquals(9007199254740993L, received.get(0).bids().get(0).volume());
    }

    @Test
    void continuesDispatchingOtherTopicsAfterInvalidQuote() {
        AtomicReference<TradeMessage> trade = new AtomicReference<>();
        AtomicReference<OddLotMessage> oddLot = new AtomicReference<>();
        dispatcher.onTrade(trade::set).onOddLot(oddLot::set);

        dispatchQuote("{\"bids\":[[45.45,1.5]]}");
        dispatcher.dispatch(envelope("trade.VNM", "{\"s\":\"VNM\",\"p\":45.45,\"q\":100}"));
        dispatcher.dispatch(envelope("oddlot.VNM", "{\"s\":\"VNM\",\"p\":45.45,\"q\":10,\"bids\":[[45.45,10]]}"));

        assertEquals("45.45", trade.get().price());
        assertEquals("100", trade.get().quantity());
        assertEquals(new PriceLevel(45.45d, 10), oddLot.get().bids().get(0));
        assertTrue(quotes.isEmpty());
    }

    private void dispatchQuote(String data) {
        dispatcher.dispatch(envelope("quote.VNM", data));
    }

    private static String envelope(String topic, String data) {
        return "{\"channel\":\"DATA\",\"topic\":\"" + topic + "\",\"data\":" + data + "}";
    }
}
