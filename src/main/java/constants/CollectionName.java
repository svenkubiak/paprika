package constants;

public final class CollectionName {
    public static final String META_COLLECTIONS = "meta.collections";
    public static final String META_HOOKS = "meta.hooks";
    public static final String TENANTS = "tenants";
    public static final String USERS = "users";
    public static final String SETTINGS = "settings";

    private CollectionName() {
    }

    public static String tenantData(String name) {
        return "_" + name;
    }

    public static String physicalTenantData(String logicalName) {
        return tenantData(logicalName);
    }

    public static String meta(String name) {
        return "meta." + name;
    }
}
