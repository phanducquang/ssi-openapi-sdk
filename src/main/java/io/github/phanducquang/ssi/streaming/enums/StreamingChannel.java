package io.github.phanducquang.ssi.streaming.enums;

import com.fasterxml.jackson.annotation.JsonValue;

public enum StreamingChannel {
    DATA("DATA"), HEARTBEAT("HEARTBEAT"), TRADING("TRADING");
    private final String value;
    StreamingChannel(String value) { this.value = value; }
    @JsonValue public String value() { return value; }
}
