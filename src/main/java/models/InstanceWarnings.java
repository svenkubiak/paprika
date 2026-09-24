package models;

import java.util.List;
import java.util.Set;

/**
 * The instance-wide problems that do not stop Paprika from running - which is exactly why nobody
 * notices them. Each list holds the names of the tenants affected and is empty when there is
 * nothing to report, so the admin UI shows a warning only when one has something to say.
 */
public record InstanceWarnings(

        /**
         * Tenants that have password reset or email verification switched on while the instance
         * has no SMTP host. The feature is configured, the API accepts the request, and the mail
         * is simply never delivered.
         */
        List<String> mailDependentTenants,

        /**
         * Tenants whose collection definitions are not covered by the unique indexes on
         * {@code name} and {@code id}, because duplicates were already present when the index was
         * created - a restored archive is the way this happens. Two definitions under one name
         * mean an edit lands on whichever of them MongoDB returns first.
         */
        List<String> degradedIndexTenants
) {
    public static InstanceWarnings none() {
        return new InstanceWarnings(List.of(), List.of());
    }

    /**
     * Derives both lists from the tenants of the instance.
     *
     * @param degradedDatabases database names as reported by
     *        {@code TenantService.degradedIndexDatabaseNames()}
     */
    public static InstanceWarnings from(
            List<TenantDefinition> tenants,
            boolean smtpConfigured,
            Set<String> degradedDatabases) {

        // Only worth saying when a tenant actually depends on mail: an instance without SMTP
        // that uses none of the recovery features is configured, not broken.
        List<String> mailDependent = smtpConfigured
                ? List.of()
                : tenants.stream()
                        .filter(tenant -> tenant.passwordResetEnabled() || tenant.emailVerificationEnabled())
                        .map(TenantDefinition::name)
                        .toList();

        List<String> degraded = degradedDatabases.isEmpty()
                ? List.of()
                : tenants.stream()
                        .filter(tenant -> degradedDatabases.contains(tenant.databaseName()))
                        .map(TenantDefinition::name)
                        .toList();

        return new InstanceWarnings(mailDependent, degraded);
    }
}
