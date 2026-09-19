package io.github.phanducquang.ssi.transport;

import java.util.Map;

public interface RestTransport extends AutoCloseable {
    RestClient.ApiResponse post(String path, Object body);

    default RestClient.ApiResponse get(String path) {
        return get(path, Map.of());
    }

    default RestClient.ApiResponse get(String path, Map<String, ?> queryParams) {
        return request("GET", path, queryParams, null, Map.of());
    }

    default RestClient.ApiResponse post(String path, Object body, Map<String, String> headers) {
        return request("POST", path, Map.of(), body, headers);
    }

    default RestClient.ApiResponse put(String path, Object body) {
        return request("PUT", path, Map.of(), body, Map.of());
    }

    default RestClient.ApiResponse put(String path, Object body, Map<String, String> headers) {
        return request("PUT", path, Map.of(), body, headers);
    }

    default RestClient.ApiResponse delete(String path, Object body) {
        return request("DELETE", path, Map.of(), body, Map.of());
    }

    default RestClient.ApiResponse delete(String path, Object body, Map<String, String> headers) {
        return request("DELETE", path, Map.of(), body, headers);
    }

    default RestClient.ApiResponse request(
            String method,
            String path,
            Map<String, ?> queryParams,
            Object body,
            Map<String, String> headers) {
        if ("POST".equalsIgnoreCase(method)
                && (queryParams == null || queryParams.isEmpty())
                && (headers == null || headers.isEmpty())) {
            return post(path, body);
        }
        throw new UnsupportedOperationException("HTTP method is not supported by this transport: " + method);
    }

    void setAccessToken(String token);

    @Override
    void close();
}
