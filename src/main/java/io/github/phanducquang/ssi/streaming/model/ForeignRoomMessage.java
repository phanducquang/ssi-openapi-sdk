package io.github.phanducquang.ssi.streaming.model;

public record ForeignRoomMessage(String tradingTime, String symbol, long totalRoom, long currentRoom, long buyQuantity, long buyValue, long sellQuantity, long sellValue) {}
