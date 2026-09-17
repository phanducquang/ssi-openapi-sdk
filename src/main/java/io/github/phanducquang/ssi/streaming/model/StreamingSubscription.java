package io.github.phanducquang.ssi.streaming.model;

import io.github.phanducquang.ssi.streaming.enums.StreamingChannel;

public record StreamingSubscription(StreamingChannel channel, String topic) {}
