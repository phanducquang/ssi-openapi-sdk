package io.github.phanducquang.ssi.trading.fco;

public enum FcoOperator {
    GREATER("greater"),
    GREATER_OR_EQUAL("greater_or_equal"),
    LESSER("lesser"),
    LESSER_OR_EQUAL("lesser_or_equal"),
    EQUAL("equal");

    private final String value;

    FcoOperator(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static FcoOperator fromValue(String value) {
        if (value == null || value.isBlank()) return null;
        for (FcoOperator operator : values()) {
            if (operator.value.equalsIgnoreCase(value) || operator.name().equalsIgnoreCase(value)) return operator;
        }
        return null;
    }
}
