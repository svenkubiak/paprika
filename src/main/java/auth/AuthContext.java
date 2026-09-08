package auth;

import enums.Role;

import java.util.Map;

public record AuthContext(String id, String role, String tenantId) {
    public static final String REQUEST_ATTRIBUTE = "paprika.auth";

    public static AuthContext guest() {
        return new AuthContext(null, null, null);
    }

    public static AuthContext of(String id, String role, String tenantId) {
        return new AuthContext(id, role, tenantId);
    }

    public static AuthContext user(String id) {
        return of(id, Role.USER, null);
    }

    public boolean isAuthenticated() {
        return id != null && !id.isBlank();
    }

    public boolean isSuperAdmin() {
        return Role.SUPERADMIN.equals(role);
    }

    public Object getField(String name) {
        return switch (name) {
            case "id" -> id;
            case "role" -> role;
            case "tenantId" -> tenantId;
            default -> null;
        };
    }

    public Map<String, Object> asMap() {
        return Map.of(
                "id", id != null ? id : "",
                "role", role != null ? role : "",
                "tenantId", tenantId != null ? tenantId : ""
        );
    }
}
