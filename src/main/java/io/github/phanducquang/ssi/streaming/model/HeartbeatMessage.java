package io.github.phanducquang.ssi.streaming.model;

public record HeartbeatMessage(String method, String channel, String status, String message) {}
