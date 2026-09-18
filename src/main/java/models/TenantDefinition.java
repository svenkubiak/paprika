package models;

import java.util.List;

public record TenantDefinition(
        String id,
        String name,
        String slug,
        String databaseName,
        String status,
        String createdAt,
        boolean registrationEnabled,
        boolean passwordResetEnabled,
        boolean emailVerificationEnabled,
        boolean emailVerificationRequired,
        String passwordResetUrl,
        String emailVerificationUrl,
        List<String> webhookAllowlist,

        /**
         * IDs of users of this tenant that may mint a session for any other user of the same
         * tenant through {@code POST /api/auth/issue-token}. Empty means nobody can.
         */
        List<String> tokenIssuers) {

    public static final String COLLECTION = "tenants";
    public static final String STATUS_ACTIVE = "active";

    public static String databaseNameFor(String tenantId) {
        return "tenant_" + tenantId;
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    public boolean canIssueTokens(String userId) {
        return userId != null && !userId.isBlank()
                && tokenIssuers != null && tokenIssuers.contains(userId);
    }
}
