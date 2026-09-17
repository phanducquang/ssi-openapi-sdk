package io.github.phanducquang.ssi.streaming.model;

import java.math.BigDecimal;

public record PutMessage(String tradingTime, String symbol, BigDecimal price, long quantity, long totalQuantity, long totalValue) {}
