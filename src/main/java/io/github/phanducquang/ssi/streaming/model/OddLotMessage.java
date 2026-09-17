package io.github.phanducquang.ssi.streaming.model;

import java.math.BigDecimal;
import java.util.List;

public record OddLotMessage(String tradingTime, String symbol, BigDecimal price, long quantity, List<PriceLevel> bids, List<PriceLevel> asks) {
    public OddLotMessage { bids = bids == null ? List.of() : List.copyOf(bids); asks = asks == null ? List.of() : List.copyOf(asks); }
}
