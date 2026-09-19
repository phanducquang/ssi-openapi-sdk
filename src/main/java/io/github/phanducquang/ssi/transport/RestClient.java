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
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

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
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.timeout())
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    @Override
    public ApiResponse post(String path, Object body) {
        return request("POST", path, Map.of(), body, Map.of());
    }

    @Override
    public ApiResponse request(
            String method,
            String path,
            Map<String, ?> queryParams,
            Object body,
            Map<String, String> headers) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");

        String requestBody = serializeBody(body);
        URI uri = buildUri(path, queryParams);

        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < config.maxRetries(); attempt++) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                        .timeout(config.timeout())
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("User-Agent", USER_AGENT)
                        .method(
                                method.toUpperCase(),
                                requestBody.isEmpty()
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(requestBody));

                String token = accessToken;
                if (token != null && !token.isBlank()) {
                    request.header("Authorization", "Bearer " + token);
                }

                if (headers != null) {
                    headers.forEach((name, value) -> {
                        if (name != null && !name.isBlank() && value != null) {
                            request.header(name, value);
                        }
                    });
                }

                HttpResponse<String> response =
                        httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
                ApiResponse apiResponse = toApiResponse(response);

                if (apiResponse.statusCode() >= 500 && attempt < config.maxRetries() - 1) {
                    sleepBackoff(attempt);
                    continue;
                }

                validateResponse(apiResponse);
                return apiResponse;
            } catch (IOException e) {
                lastFailure = new SsiApiException("SSI request I/O failure: " + e.getMessage(), e);
                if (attempt < config.maxRetries() - 1) {
                    sleepBackoff(attempt);
                    continue;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SsiApiException("SSI request interrupted", e);
            }
        }

        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new SsiApiException("SSI request failed after retries", 0, null);
    }

    @Override
    public void setAccessToken(String token) {
        this.accessToken = token;
    }

    URI buildUri(String path, Map<String, ?> queryParams) {
        URI base = config.apiUrl().resolve(path);
        if (queryParams == null || queryParams.isEmpty()) {
            return base;
        }

        StringJoiner query = new StringJoiner("&");
        queryParams.forEach((key, value) -> appendQueryParam(query, key, value));
        if (query.length() == 0) {
            return base;
        }

        String separator = base.getRawQuery() == null ? "?" : "&";
        return URI.create(base + separator + query);
    }

    private void appendQueryParam(StringJoiner query, String key, Object value) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }

        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null) {
                    appendSingleQueryParam(query, key, item);
                }
            }
            return;
        }

        appendSingleQueryParam(query, key, value);
    }

    private void appendSingleQueryParam(StringJoiner query, String key, Object value) {
        query.add(encode(key) + "=" + encode(String.valueOf(value)));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String serializeBody(Object body) {
        if (body == null) {
            return "";
        }
        if (body instanceof String rawBody) {
            return rawBody;
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new SsiApiException("Failed to serialize SSI request body", e);
        }
    }

    private ApiResponse toApiResponse(HttpResponse<String> response) {
        JsonNode body = NullNode.getInstance();
        String raw = response.body();
        if (raw != null && !raw.isBlank()) {
            try {
                body = objectMapper.readTree(raw);
            } catch (JsonProcessingException e) {
                log.debug(
                        "SSI response is not JSON. status={}, body={}",
                        response.statusCode(),
                        raw);
            }
        }
        return new ApiResponse(response.statusCode(), body, response.headers());
    }

    private void validateResponse(ApiResponse response) {
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new AuthenticationException(
                    "SSI authentication failed: HTTP " + status,
                    status,
                    response.body());
        }
        if (status == 429) {
            throw new RateLimitException(
                    "SSI rate limit exceeded",
                    status,
                    response.body(),
                    parseRetryAfter(response.headers()));
        }
        if (status >= 400) {
            throw new SsiApiException(
                    "SSI API error: HTTP " + status,
                    status,
                    response.body());
        }
    }

    private Double parseRetryAfter(HttpHeaders headers) {
        return headers.firstValue("Retry-After")
                .map(value -> {
                    try {
                        return Double.parseDouble(value);
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                })
                .orElse(null);
    }

    private void sleepBackoff(int attempt) {
        Duration base = config.retryDelay();
        long multiplier = 1L << Math.min(attempt, 20);
        long millis;
        try {
            millis = Math.multiplyExact(base.toMillis(), multiplier);
        } catch (ArithmeticException e) {
            millis = Long.MAX_VALUE;
        }

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SsiApiException("Interrupted during SSI retry backoff", e);
        }
    }

    @Override
    public void close() {
        // java.net.http.HttpClient does not require explicit shutdown on Java 17.
    }

    public record ApiResponse(int statusCode, JsonNode body, HttpHeaders headers) {}
}
