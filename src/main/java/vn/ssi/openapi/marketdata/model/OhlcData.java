package vn.ssi.openapi.marketdata.model;

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
