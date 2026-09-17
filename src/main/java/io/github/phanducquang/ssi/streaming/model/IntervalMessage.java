package io.github.phanducquang.ssi.streaming.model;

import java.math.BigDecimal;

public record IntervalMessage(String intervalTime, String tradingTime, String symbol, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {}
