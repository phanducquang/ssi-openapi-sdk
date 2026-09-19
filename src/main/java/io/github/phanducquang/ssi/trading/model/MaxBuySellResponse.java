package io.github.phanducquang.ssi.trading.model;

public record MaxBuySellResponse(
        String accountNo,
        String symbol,
        long maxBuyQuantity,
        long maxSellQuantity,
        String marginRatio,
        String purchasePower) {
}
