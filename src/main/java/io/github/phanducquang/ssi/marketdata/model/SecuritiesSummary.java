package io.github.phanducquang.ssi.marketdata.model;

public record SecuritiesSummary(
        String symbol,
        String tradingDate,
        double priceChange,
        double priceChangePercent,
        double openPrice,
        double highPrice,
        double lowPrice,
        double closePrice,
        double averagePrice,
        long totalMatch,
        double totalMatchValue,
        long totalBuy,
        double totalTradeBuy,
        long totalSell,
        double totalTradeSell,
        long totalForeignBuy,
        double totalForeignBuyValue,
        long totalForeignSell,
        double totalForeignSellValue,
        long remainForeignRoom,
        long totalForeignRoom,
        long totalDeal,
        double totalDealValue,
        double openInterest,
        double settlementPrice) {
}
