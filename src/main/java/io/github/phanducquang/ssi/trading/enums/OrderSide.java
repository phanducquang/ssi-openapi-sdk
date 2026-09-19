package io.github.phanducquang.ssi.trading.enums;

public enum OrderSide {
    BUY("B"),
    SELL("S");

    private final String value;

    OrderSide(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static OrderSide fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (OrderSide side : values()) {
            if (side.name().equalsIgnoreCase(value) || side.value.equalsIgnoreCase(value)) {
                return side;
            }
        }
        return null;
    }
}
