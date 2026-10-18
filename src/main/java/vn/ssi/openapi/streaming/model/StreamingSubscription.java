package vn.ssi.openapi.streaming.model;

import vn.ssi.openapi.streaming.enums.StreamingChannel;

public record StreamingSubscription(StreamingChannel channel, String topic) {}
