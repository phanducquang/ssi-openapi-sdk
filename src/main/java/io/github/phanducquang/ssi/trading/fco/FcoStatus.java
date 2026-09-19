package io.github.phanducquang.ssi.trading.fco;

public enum FcoStatus {
    INIT("INIT"),
    WAIT("WAIT"),
    TRI("TRI"),
    TRIT("TRIT"),
    TER("TER"),
    FIS("FIS"),
    WC("WC"),
    EXP("EXP"),
    ERR("ERR");

    private final String value;

    FcoStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static FcoStatus fromValue(String value) {
        if (value == null || value.isBlank()) return null;
        for (FcoStatus status : values()) {
            if (status.value.equalsIgnoreCase(value) || status.name().equalsIgnoreCase(value)) return status;
        }
        return null;
    }
}
