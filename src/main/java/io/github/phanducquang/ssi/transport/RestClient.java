package io.github.phanducquang.ssi.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.github.phanducquang.ssi.config.SsiConfig;
import io.github.phanducquang.ssi.exception.AuthenticationException;
import io.github.phanducquang.ssi.exception.RateLimitException;
import io.github.phanducquang.ssi.exception.SsiApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

public final class RestClient implements RestTransport {
    private static final Logger log = LoggerFactory.getLogger(RestClient.class);
    private static final String USER_AGENT = "ssi-openapi-sdk-java/0.1";
    private final SsiConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private volatile String accessToken;

    public RestClient(SsiConfig config, ObjectMapper objectMapper) {
        this.config = Objects.requireNonNull(config);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.httpClient = HttpClient.newBuilder().connectTimeout(config.timeout()).version(HttpClient.Version.HTTP_2).build();
    }

    @Override public ApiResponse post(String path, Object body) { return send("POST", path, body); }

    public ApiResponse send(String method, String path, Object body) {
        String json;
        try { json = body == null ? "" : objectMapper.writeValueAsString(body); }
        catch (JsonProcessingException e) { throw new SsiApiException("Failed to serialize SSI request body", e); }

        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < config.maxRetries(); attempt++) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(config.apiUrl().resolve(path))
                        .timeout(config.timeout())
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("User-Agent", USER_AGENT)
                        .method(method, json.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
                String token = accessToken;
                if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token);

                HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
                ApiResponse apiResponse = toApiResponse(response);
                if (apiResponse.statusCode() >= 500 && attempt < config.maxRetries() - 1) { sleepBackoff(attempt); continue; }
                validateResponse(apiResponse);
                return apiResponse;
            } catch (IOException e) {
                lastFailure = new SsiApiException("SSI request I/O failure: " + e.getMessage(), e);
                if (attempt < config.maxRetries() - 1) { sleepBackoff(attempt); continue; }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SsiApiException("SSI request interrupted", e);
            }
        }
        if (lastFailure != null) throw lastFailure;
        throw new SsiApiException("SSI request failed after retries", 0, null);
    }

    @Override public void setAccessToken(String token) { this.accessToken = token; }

    private ApiResponse toApiResponse(HttpResponse<String> response) {
        JsonNode body = NullNode.getInstance();
        String raw = response.body();
        if (raw != null && !raw.isBlank()) {
            try { body = objectMapper.readTree(raw); }
            catch (JsonProcessingException e) { log.debug("SSI response is not JSON. status={}, body={}", response.statusCode(), raw); }
        }
        return new ApiResponse(response.statusCode(), body, response.headers());
    }

    private void validateResponse(ApiResponse response) {
        int status = response.statusCode();
        if (status == 401 || status == 403) throw new AuthenticationException("SSI authentication failed: HTTP " + status, status, response.body());
        if (status == 429) throw new RateLimitException("SSI rate limit exceeded", status, response.body(), parseRetryAfter(response.headers()));
        if (status >= 400) throw new SsiApiException("SSI API error: HTTP " + status, status, response.body());
    }

    private Double parseRetryAfter(HttpHeaders headers) {
        return headers.firstValue("Retry-After").map(value -> {
            try { return Double.parseDouble(value); } catch (NumberFormatException ignored) { return null; }
        }).orElse(null);
    }

    private void sleepBackoff(int attempt) {
        Duration base = config.retryDelay();
        long multiplier = 1L << Math.min(attempt, 20);
        long millis;
        try { millis = Math.multiplyExact(base.toMillis(), multiplier); }
        catch (ArithmeticException e) { millis = Long.MAX_VALUE; }
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new SsiApiException("Interrupted during SSI retry backoff", e); }
    }

    @Override public void close() { }
    public record ApiResponse(int statusCode, JsonNode body, HttpHeaders headers) {}
}
