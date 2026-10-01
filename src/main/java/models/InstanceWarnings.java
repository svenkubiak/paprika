package models;

import java.util.List;
import java.util.Set;

public record InstanceWarnings(
        // Recovery features enabled without an SMTP host: the API accepts, the mail never arrives.
        List<String> mailDependentTenants,

        // Duplicate definitions (e.g. from a restored archive) blocked the unique name/id indexes.
        List<String> degradedIndexTenants
) {
    public static InstanceWarnings none() {
        return new InstanceWarnings(List.of(), List.of());
    }

    public static InstanceWarnings from(
            List<TenantDefinition> tenants,
            boolean smtpConfigured,
            Set<String> degradedDatabases) {

        // An instance without SMTP that uses no recovery feature is configured, not broken.
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
