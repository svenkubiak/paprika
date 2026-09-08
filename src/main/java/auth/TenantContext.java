package auth;

import enums.Role;

public record TenantContext(
        String userId,
        String role,
        String boundTenantId,
        String activeTenantId,
        String databaseName) {

    public static final String REQUEST_ATTRIBUTE = "paprika.tenant";

    public boolean isSuperAdmin() {
        return Role.SUPERADMIN.equals(role);
    }

    public boolean hasTenantContext() {
        return databaseName != null && !databaseName.isBlank();
    }

    public boolean hasAuthenticatedUser() {
        return userId != null && !userId.isBlank();
    }

    public String effectiveTenantId() {
        return activeTenantId;
    }

    public static TenantContext of(AuthContext auth, String databaseName) {
        return new TenantContext(
                auth.id(),
                auth.role(),
                auth.tenantId(),
                auth.tenantId(),
                databaseName
        );
    }

    public static TenantContext guest(String tenantId, String databaseName) {
        return new TenantContext(null, null, tenantId, tenantId, databaseName);
    }
}
