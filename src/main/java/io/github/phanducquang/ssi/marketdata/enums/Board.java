package io.github.phanducquang.ssi.marketdata.enums;

public enum Board {
    HOSE("HOSE"),
    HNX("HNX"),
    UPCOM("UPCOM"),
    DERIVATIVES("DERIVATIVES");

    private final String value;

    Board(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Board fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (Board board : values()) {
            if (board.value.equalsIgnoreCase(value)) {
                return board;
            }
        }
        return null;
    }
}
