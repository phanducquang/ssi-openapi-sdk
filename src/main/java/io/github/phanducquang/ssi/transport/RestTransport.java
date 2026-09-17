package io.github.phanducquang.ssi.transport;

public interface RestTransport extends AutoCloseable {
    RestClient.ApiResponse post(String path, Object body);
    void setAccessToken(String token);
    @Override void close();
}
