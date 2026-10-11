package vn.ssi.openapi.streaming.model;

import vn.ssi.openapi.streaming.enums.StreamingChannel;
import vn.ssi.openapi.streaming.enums.StreamingMethod;
import java.util.List;

public record StreamingRequest(StreamingMethod method, StreamingChannel channel, List<String> topics) {
    public StreamingRequest { topics = topics == null ? List.of() : List.copyOf(topics); }
}
