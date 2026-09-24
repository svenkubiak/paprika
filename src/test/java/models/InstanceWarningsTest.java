package models;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * The warnings decide what interrupts a superadmin on the dashboard, so the rule for each of them
 * is pinned here rather than only being reachable through the bootstrap payload - which cannot
 * produce the "no SMTP" case at all, because the test instance has one.
 */
class InstanceWarningsTest {

    @Test
    void mailWarningNamesOnlyTheTenantsThatDependOnMail() {
        List<TenantDefinition> tenants = List.of(
                tenant("Reset", true, false),
                tenant("Verify", false, true),
                tenant("Both", true, true),
                tenant("Neither", false, false));

        InstanceWarnings warnings = InstanceWarnings.from(tenants, false, Set.of());

        assertThat(warnings.mailDependentTenants(), contains("Reset", "Verify", "Both"));
    }

    @Test
    void noMailWarningWhileAnSmtpHostIsConfigured() {
        List<TenantDefinition> tenants = List.of(tenant("Reset", true, false));

        InstanceWarnings warnings = InstanceWarnings.from(tenants, true, Set.of());

        assertThat(warnings.mailDependentTenants(), is(empty()));
    }

    @Test
    void noMailWarningWithoutSmtpWhenNoTenantUsesTheRecoveryFeatures() {
        List<TenantDefinition> tenants = List.of(tenant("Neither", false, false));

        InstanceWarnings warnings = InstanceWarnings.from(tenants, false, Set.of());

        assertThat(warnings.mailDependentTenants(), is(empty()));
    }

    @Test
    void degradedIndexWarningResolvesDatabaseNamesToTenantNames() {
        TenantDefinition affected = tenant("Restored", false, false);
        List<TenantDefinition> tenants = List.of(affected, tenant("Healthy", false, false));

        InstanceWarnings warnings = InstanceWarnings.from(tenants, true, Set.of(affected.databaseName()));

        assertThat(warnings.degradedIndexTenants(), contains("Restored"));
    }

    /**
     * A database name left over from a tenant that no longer exists must not produce a warning
     * without a name to show for it.
     */
    @Test
    void degradedIndexWarningIgnoresDatabasesWithoutATenant() {
        List<TenantDefinition> tenants = List.of(tenant("Healthy", false, false));

        InstanceWarnings warnings = InstanceWarnings.from(tenants, true, Set.of("tenant_gone"));

        assertThat(warnings.degradedIndexTenants(), is(empty()));
    }

    @Test
    void noneReportsNothing() {
        assertThat(InstanceWarnings.none().mailDependentTenants(), is(empty()));
        assertThat(InstanceWarnings.none().degradedIndexTenants(), is(empty()));
    }

    private static TenantDefinition tenant(String name, boolean passwordReset, boolean emailVerification) {
        String id = "id-" + name;
        return new TenantDefinition(
                id,
                name,
                name.toLowerCase(java.util.Locale.ROOT),
                TenantDefinition.databaseNameFor(id),
                TenantDefinition.STATUS_ACTIVE,
                "2026-01-01T00:00:00Z",
                false,
                passwordReset,
                emailVerification,
                false,
                null,
                null,
                List.of(),
                List.of());
    }
}
