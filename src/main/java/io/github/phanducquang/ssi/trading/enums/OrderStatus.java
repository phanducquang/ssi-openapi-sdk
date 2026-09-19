package io.github.phanducquang.ssi.trading.enums;

public enum OrderStatus {
    PENDING("PD"),
    PENDING_APPROVAL("WA"),
    READY("RS"),
    SENT("SD"),
    QUEUED("QU"),
    FILLED("FF"),
    PARTIAL_FILLED("PF"),
    PARTIAL_CANCELLED("FFPC"),
    PENDING_MODIFY("WM"),
    PENDING_CANCEL("WC"),
    CANCELLED("CL"),
    REJECTED("RJ"),
    EXPIRED("EX"),
    PRE_SESSION("IAV");

    private final String value;

    OrderStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static OrderStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (OrderStatus status : values()) {
            if (status.name().equalsIgnoreCase(value) || status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        return null;
    }
}
