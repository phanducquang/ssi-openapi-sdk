package vn.ssi.openapi.streaming.model;

public record PutMessage(String tradingTime, String symbol, String price, String quantity, String totalQuantity, String totalValue) {}
