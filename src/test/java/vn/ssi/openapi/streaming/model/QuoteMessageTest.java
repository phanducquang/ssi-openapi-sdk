package vn.ssi.openapi.streaming.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QuoteMessageTest {
    @Test
    void legacyConstructorInfersPresenceBeforeNormalizingNullLists() {
        QuoteMessage missingBids = new QuoteMessage("", "VNM", null, List.of());
        QuoteMessage missingAsks = new QuoteMessage("", "VNM", List.of(), null);

        assertFalse(missingBids.bidsPresent());
        assertTrue(missingBids.asksPresent());
        assertTrue(missingBids.bids().isEmpty());
        assertTrue(missingAsks.bidsPresent());
        assertFalse(missingAsks.asksPresent());
        assertTrue(missingAsks.asks().isEmpty());
    }

    @Test
    void copiesSidesAndExposesImmutableLists() {
        List<PriceLevel> bids = new ArrayList<>(List.of(new PriceLevel(45.45d, 100)));
        List<PriceLevel> asks = new ArrayList<>(List.of(new PriceLevel(46d, 200)));
        QuoteMessage quote = new QuoteMessage("", "VNM", bids, asks, true, true);

        bids.clear();
        asks.clear();

        assertEquals(List.of(new PriceLevel(45.45d, 100)), quote.bids());
        assertEquals(List.of(new PriceLevel(46d, 200)), quote.asks());
        assertThrows(UnsupportedOperationException.class, () -> quote.bids().clear());
        assertThrows(UnsupportedOperationException.class, () -> quote.asks().clear());
    }
}
