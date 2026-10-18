package vn.ssi.openapi.streaming.model;

public record ForeignRoomMessage(String tradingTime, String symbol, String totalRoom, String currentRoom, String buyQuantity, String buyValue, String sellQuantity, String sellValue) {}
