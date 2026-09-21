package constants;

public final class SettingKeys {
    public static final String REQUEST_LOG_RETENTION_DAYS = "request.log.retention.days";
    public static final String DEFAULT_TENANT_ID = "default.tenant.id";

    /*
     * User agent and client IP are personal data, so both are opt-in and default to off: a fresh
     * installation must not collect more about a caller than it needs to answer the call.
     */
    public static final String REQUEST_LOG_CLIENT_INFO = "request.log.client.info";
    public static final String REQUEST_LOG_CLIENT_IP = "request.log.client.ip";

    public static final String CLIENT_IP_OFF = "off";
    public static final String CLIENT_IP_TRUNCATED = "truncated";
    public static final String CLIENT_IP_FULL = "full";

    public static final int DEFAULT_REQUEST_LOG_RETENTION_DAYS = 7;

    private SettingKeys() {
    }
}
