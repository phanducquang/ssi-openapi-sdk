package vn.ssi.openapi.streaming.model;

import java.util.List;

public record QuoteMessage(String tradingTime, String symbol, List<PriceLevel> bids, List<PriceLevel> asks,
                           boolean bidsPresent, boolean asksPresent) {
    public QuoteMessage {
        bids = bids == null ? List.of() : List.copyOf(bids);
        asks = asks == null ? List.of() : List.copyOf(asks);
    }

    public QuoteMessage(String tradingTime, String symbol, List<PriceLevel> bids, List<PriceLevel> asks) {
        this(tradingTime, symbol, bids, asks, bids != null, asks != null);
    }
}
