package constants;

public final class SettingKeys {
    public static final String REQUEST_LOG_RETENTION_DAYS = "request.log.retention.days";
    public static final String DEFAULT_TENANT_ID = "default.tenant.id";

    // Personal data, so both are opt-in and default to off.
    public static final String REQUEST_LOG_CLIENT_INFO = "request.log.client.info";
    public static final String REQUEST_LOG_CLIENT_IP = "request.log.client.ip";

    // Admin UI calls would bury the API traffic, so they are only logged on request (e.g. for audits).
    public static final String REQUEST_LOG_ADMIN_UI = "request.log.admin.ui";

    public static final String CLIENT_IP_OFF = "off";
    public static final String CLIENT_IP_TRUNCATED = "truncated";
    public static final String CLIENT_IP_FULL = "full";

    public static final int DEFAULT_REQUEST_LOG_RETENTION_DAYS = 7;

    private SettingKeys() {
    }
}
