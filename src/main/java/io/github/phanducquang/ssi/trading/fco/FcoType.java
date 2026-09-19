package io.github.phanducquang.ssi.trading.fco;

public enum FcoType {
    GTD("gtd"),
    STOP("stop"),
    STOP_LIMIT("stop_limit"),
    TRAILING_STOP("trailing_stop"),
    TRAILING_STOP_LIMIT("trailing_stop_limit"),
    OCO("oco"),
    BULL_BEAR("bullbear");

    private final String value;

    FcoType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static FcoType fromValue(String value) {
        if (value == null || value.isBlank()) return null;
        for (FcoType type : values()) {
            if (type.value.equalsIgnoreCase(value) || type.name().equalsIgnoreCase(value)) return type;
        }
        return null;
    }
}
