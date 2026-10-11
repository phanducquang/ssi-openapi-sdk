package vn.ssi.openapi.streaming.model;

public record TradeMessage(
        String tradingTime,
        String symbol,
        String price,
        String quantity,
        String avgPrice,
        String side,
        String openPrice,
        String highPrice,
        String lowPrice,
        String totalVolume
) {}
