package io.github.phanducquang.ssi.streaming.model;

import java.util.List;

public record QuoteMessage(String tradingTime, String symbol, List<PriceLevel> bids, List<PriceLevel> asks) {
    public QuoteMessage { bids = bids == null ? List.of() : List.copyOf(bids); asks = asks == null ? List.of() : List.copyOf(asks); }
}
