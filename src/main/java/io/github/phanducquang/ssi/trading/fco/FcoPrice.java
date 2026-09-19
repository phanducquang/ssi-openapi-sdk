package io.github.phanducquang.ssi.trading.fco;

import io.github.phanducquang.ssi.trading.enums.OrderType;

import java.math.BigDecimal;
import java.util.Objects;

public record FcoPrice(String value, boolean orderType) {
    public FcoPrice {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("FCO price value is required");
        }
    }

    public static FcoPrice fixed(double value) {
        if (value < 0.0d) {
            throw new IllegalArgumentException("FCO price must be non-negative");
        }
        return new FcoPrice(BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(), false);
    }

    public static FcoPrice orderType(OrderType orderType) {
        return new FcoPrice(Objects.requireNonNull(orderType, "orderType").value(), true);
    }

    public double effectiveSlip(double requestedSlip) {
        return orderType ? 0.0d : requestedSlip;
    }
}
