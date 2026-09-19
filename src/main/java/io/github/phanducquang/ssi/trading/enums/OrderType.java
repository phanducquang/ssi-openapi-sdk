package io.github.phanducquang.ssi.trading.enums;

public enum OrderType {
    ATO("ATO"),
    ATC("ATC"),
    LO("LO"),
    MTL("MTL"),
    MP("MP"),
    MOK("MOK"),
    MAK("MAK"),
    PLO("PLO");

    private final String value;

    OrderType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static OrderType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (OrderType type : values()) {
            if (type.name().equalsIgnoreCase(value) || type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        return null;
    }
}
