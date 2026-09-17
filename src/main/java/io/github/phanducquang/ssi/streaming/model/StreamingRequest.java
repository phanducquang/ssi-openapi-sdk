package io.github.phanducquang.ssi.streaming.model;

import io.github.phanducquang.ssi.streaming.enums.StreamingChannel;
import io.github.phanducquang.ssi.streaming.enums.StreamingMethod;
import java.util.List;

public record StreamingRequest(StreamingMethod method, StreamingChannel channel, List<String> topics) {
    public StreamingRequest { topics = topics == null ? List.of() : List.copyOf(topics); }
}
