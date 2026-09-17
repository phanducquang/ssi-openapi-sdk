package io.github.phanducquang.ssi.streaming.enums;

public enum Timeframe {
    MINUTE_1("1m"), MINUTE_3("3m"), MINUTE_5("5m"), MINUTE_15("15m"), HOUR_1("1h"), DAY_1("1d"), WEEK_1("1w"), MONTH_1("1M");
    private final String value;
    Timeframe(String value) { this.value = value; }
    public String value() { return value; }
}
