package io.github.phanducquang.ssi.streaming.enums;

import com.fasterxml.jackson.annotation.JsonValue;

public enum StreamingMethod {
    SUBSCRIBE("subscribe"),
    UNSUBSCRIBE("unsubscribe"),
    PING_PONG("ping_pong"),
    LIST_SUBSCRIPTION("list_subscription");

    private final String value;
    StreamingMethod(String value) { this.value = value; }
    @JsonValue public String value() { return value; }
}
