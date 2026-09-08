package constants;

public final class SystemCollections {
    public static final String USERS = "users";
    public static final String SETTINGS = "settings";
    public static final String REQUEST_LOGS = "request_logs";

    private SystemCollections() {
    }

    public static boolean isSystem(String name) {
        return USERS.equals(name) || SETTINGS.equals(name) || REQUEST_LOGS.equals(name);
    }

    public static boolean isVisibleInAdmin(String name) {
        return !isSystem(name);
    }

    public static boolean isRelationTarget(String name) {
        return USERS.equals(name);
    }
}
