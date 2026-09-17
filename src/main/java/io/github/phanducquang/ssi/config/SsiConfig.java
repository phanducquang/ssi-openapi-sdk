package io.github.phanducquang.ssi.config;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

public final class SsiConfig {
    public static final URI DEFAULT_API_URL = URI.create("https://api.ssi.com.vn");
    public static final URI DEFAULT_STREAMING_URL = URI.create("wss://stream.ssi.com.vn/ws/v3");

    private final String clientId;
    private final String apiKey;
    private final String apiSecret;
    private final String privateKey;
    private final URI apiUrl;
    private final URI streamingUrl;
    private final Duration timeout;
    private final int maxRetries;
    private final Duration retryDelay;
    private final Duration smartOtpPollInterval;
    private final int smartOtpPollMaxRetries;

    private SsiConfig(Builder builder) {
        this.clientId = builder.clientId;
        this.apiKey = builder.apiKey;
        this.apiSecret = builder.apiSecret;
        this.privateKey = builder.privateKey;
        this.apiUrl = builder.apiUrl;
        this.streamingUrl = builder.streamingUrl;
        this.timeout = builder.timeout;
        this.maxRetries = builder.maxRetries;
        this.retryDelay = builder.retryDelay;
        this.smartOtpPollInterval = builder.smartOtpPollInterval;
        this.smartOtpPollMaxRetries = builder.smartOtpPollMaxRetries;
    }

    public static Builder builder() { return new Builder(); }
    public String clientId() { return clientId; }
    public String apiKey() { return apiKey; }
    public String apiSecret() { return apiSecret; }
    public String privateKey() { return privateKey; }
    public URI apiUrl() { return apiUrl; }
    public URI streamingUrl() { return streamingUrl; }
    public Duration timeout() { return timeout; }
    public int maxRetries() { return maxRetries; }
    public Duration retryDelay() { return retryDelay; }
    public Duration smartOtpPollInterval() { return smartOtpPollInterval; }
    public int smartOtpPollMaxRetries() { return smartOtpPollMaxRetries; }

    public void validateCredentials() {
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
            throw new IllegalStateException("apiKey and apiSecret are required for SSI authentication");
        }
    }

    public static final class Builder {
        private String clientId = "";
        private String apiKey = "";
        private String apiSecret = "";
        private String privateKey = "";
        private URI apiUrl = DEFAULT_API_URL;
        private URI streamingUrl = DEFAULT_STREAMING_URL;
        private Duration timeout = Duration.ofSeconds(60);
        private int maxRetries = 5;
        private Duration retryDelay = Duration.ofSeconds(2);
        private Duration smartOtpPollInterval = Duration.ofSeconds(5);
        private int smartOtpPollMaxRetries = 5;

        public Builder clientId(String clientId) { this.clientId = Objects.requireNonNullElse(clientId, ""); return this; }
        public Builder apiKey(String apiKey) { this.apiKey = Objects.requireNonNullElse(apiKey, ""); return this; }
        public Builder apiSecret(String apiSecret) { this.apiSecret = Objects.requireNonNullElse(apiSecret, ""); return this; }
        public Builder privateKey(String privateKey) { this.privateKey = Objects.requireNonNullElse(privateKey, ""); return this; }
        public Builder apiUrl(String apiUrl) { return apiUrl(URI.create(apiUrl)); }
        public Builder apiUrl(URI apiUrl) { this.apiUrl = Objects.requireNonNull(apiUrl); return this; }
        public Builder streamingUrl(String streamingUrl) { return streamingUrl(URI.create(streamingUrl)); }
        public Builder streamingUrl(URI streamingUrl) { this.streamingUrl = Objects.requireNonNull(streamingUrl); return this; }
        public Builder timeout(Duration timeout) { this.timeout = requirePositive(timeout, "timeout"); return this; }
        public Builder maxRetries(int maxRetries) { if (maxRetries < 1) throw new IllegalArgumentException("maxRetries must be >= 1"); this.maxRetries = maxRetries; return this; }
        public Builder retryDelay(Duration retryDelay) { this.retryDelay = requireNonNegative(retryDelay, "retryDelay"); return this; }
        public Builder smartOtpPollInterval(Duration interval) { this.smartOtpPollInterval = requirePositive(interval, "smartOtpPollInterval"); return this; }
        public Builder smartOtpPollMaxRetries(int retries) { if (retries < 1) throw new IllegalArgumentException("smartOtpPollMaxRetries must be >= 1"); this.smartOtpPollMaxRetries = retries; return this; }
        public SsiConfig build() { return new SsiConfig(this); }

        private static Duration requirePositive(Duration value, String name) { Objects.requireNonNull(value, name); if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be > 0"); return value; }
        private static Duration requireNonNegative(Duration value, String name) { Objects.requireNonNull(value, name); if (value.isNegative()) throw new IllegalArgumentException(name + " must be >= 0"); return value; }
    }
}
