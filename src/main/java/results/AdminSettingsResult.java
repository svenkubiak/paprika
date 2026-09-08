package results;

import java.util.Map;

public record AdminSettingsResult(AdminSettingsResult.Status status, Map<String, Object> body, String errorMessage) {
    public enum Status {
        OK,
        BAD_REQUEST,
        UNAUTHORIZED,
        NOT_FOUND
    }

    public static AdminSettingsResult ok(Map<String, Object> body) {
        return new AdminSettingsResult(Status.OK, body, null);
    }

    public static AdminSettingsResult badRequest(String errorMessage) {
        return new AdminSettingsResult(Status.BAD_REQUEST, null, errorMessage);
    }

    public static AdminSettingsResult unauthorized(String errorMessage) {
        return new AdminSettingsResult(Status.UNAUTHORIZED, null, errorMessage);
    }

    public static AdminSettingsResult notFound(String errorMessage) {
        return new AdminSettingsResult(Status.NOT_FOUND, null, errorMessage);
    }
}
