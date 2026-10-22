package vn.ssi.openapi.config;

/** Controls whether runtime recovery may authenticate without an OTP. */
public enum ReconnectAuthenticationPolicy {
    REFRESH_ONLY,
    API_CREDENTIALS
}
