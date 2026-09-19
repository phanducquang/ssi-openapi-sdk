package io.github.phanducquang.ssi.marketdata.model;

public record OhlcData(
        String symbol,
        String tradingDate,
        double openPrice,
        double highPrice,
        double lowPrice,
        double closePrice,
        long volume,
        double value) {
}
