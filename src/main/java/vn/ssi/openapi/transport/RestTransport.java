package vn.ssi.openapi.transport;

public interface RestTransport extends AutoCloseable {
    RestClient.ApiResponse post(String path, Object body);

    default RestClient.ApiResponse get(String path) {
        return get(path, java.util.Map.of());
    }

    default RestClient.ApiResponse get(String path, java.util.Map<String, ?> params) {
        return request("GET", path, params, null, java.util.Map.of());
    }

    default RestClient.ApiResponse put(String path, Object body) {
        return request("PUT", path, java.util.Map.of(), body, java.util.Map.of());
    }

    default RestClient.ApiResponse delete(String path, Object body) {
        return request("DELETE", path, java.util.Map.of(), body, java.util.Map.of());
    }

    default RestClient.ApiResponse request(
            String method,
            String path,
            java.util.Map<String, ?> queryParams,
            Object body,
            java.util.Map<String, String> headers) {
        if ("POST".equalsIgnoreCase(method)
                && (queryParams == null || queryParams.isEmpty())
                && (headers == null || headers.isEmpty())) {
            return post(path, body);
        }
        throw new UnsupportedOperationException("Unsupported HTTP request method: " + method);
    }

    void setAccessToken(String token);
    @Override void close();
}
