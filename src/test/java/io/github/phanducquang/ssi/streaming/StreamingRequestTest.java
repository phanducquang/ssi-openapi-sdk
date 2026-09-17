package io.github.phanducquang.ssi.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.phanducquang.ssi.streaming.enums.StreamingChannel;
import io.github.phanducquang.ssi.streaming.enums.StreamingMethod;
import io.github.phanducquang.ssi.streaming.model.StreamingRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamingRequestTest {
    @Test
    void serializesUsingSsiProtocolValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var node = mapper.readTree(mapper.writeValueAsString(new StreamingRequest(StreamingMethod.SUBSCRIBE, StreamingChannel.DATA, List.of("trade.VNM", "quote.VNM"))));
        assertEquals("subscribe", node.path("method").asText());
        assertEquals("DATA", node.path("channel").asText());
        assertEquals("trade.VNM", node.path("topics").get(0).asText());
    }
}
