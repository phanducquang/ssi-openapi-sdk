package io.github.phanducquang.ssi.streaming.model;

import java.math.BigDecimal;

public record TradeMessage(String tradingTime, String symbol, BigDecimal price, long quantity, String side, long totalVolume) {}
