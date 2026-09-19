package io.github.phanducquang.ssi.marketdata.model;

public record MarketIndexSummary(
        String tradingDate,
        long totalTrade,
        double totalTradeValue,
        long totalMatch,
        double totalMatchValue,
        long totalDeal,
        double totalDealValue,
        double indexChange,
        double indexChangePercent,
        double indexValue,
        long totalAdvanceStock,
        long totalDeclineStock,
        long totalSteadyStock,
        long totalCeilingStock,
        long totalFloorStock,
        long totalPropBuy,
        double totalPropBuyValue,
        long totalPropSell,
        double totalPropSellValue) {
}
