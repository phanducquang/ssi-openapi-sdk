package io.github.phanducquang.ssi.streaming.enums;

public enum DataTopic {
    TRADE("trade"), QUOTE("quote"), ROOM("room"), MARKET("market"), PUT("put"), ODD_LOT("oddlot");
    private final String prefix;
    DataTopic(String prefix) { this.prefix = prefix; }
    public String prefix() { return prefix; }
}
