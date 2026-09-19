package io.github.phanducquang.ssi.account.model;

public enum AccountType {
    EQUITY("Cash"),
    EQUITY_MARGIN("Margin"),
    DERIVATIVE("Derivative");

    private final String value;

    AccountType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static AccountType fromValue(String value) {
        for (AccountType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported SSI account type: " + value);
    }
}
