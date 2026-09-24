package services;

import auth.TenantContext;
import constants.SettingKeys;
import io.mangoo.core.Application;
import models.TenantDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@ExtendWith(io.mangoo.test.TestRunner.class)
class TenantServiceTest {
    private static final String STATUS_INACTIVE = "inactive";

    @Test
    void deleteWithCascadeRemovesTenantStorageDirectory() throws Exception {
        TenantService tenantService = Application.getInstance(TenantService.class);
        FileStorageService fileStorageService = Application.getInstance(FileStorageService.class);

        TenantDefinition tenant = tenantService.create("Delete Cascade Test", "delete-cascade-test");
        TenantContext ctx = TenantContext.guest(tenant.id(), tenant.databaseName());
        fileStorageService.store(ctx, "file-1", "data".getBytes());

        assertThat(Files.exists(fileStorageService.root().resolve(tenant.id())), is(true));

        boolean deleted = tenantService.deleteWithCascade(tenant.id());

        assertThat(deleted, is(true));
        assertThat(Files.exists(fileStorageService.root().resolve(tenant.id())), is(false));
        assertThat(tenantService.findById(tenant.id()).isPresent(), is(false));
    }

    @Test
    void bootstrapMakesTheFirstTenantTheConfiguredDefault() {
        TenantService tenantService = Application.getInstance(TenantService.class);
        SettingsService settingsService = Application.getInstance(SettingsService.class);

        // The test instance starts on an empty database, so the bootstrapped tenant is the first
        // one that ever existed - and the default is written down instead of being inferred from
        // its slug.
        TenantDefinition bootstrapped = tenantService.findBySlug("default").orElseThrow();

        assertThat(settingsService.get(SettingKeys.DEFAULT_TENANT_ID, null), is(bootstrapped.id()));
    }

    @Test
    void ensureDefaultTenantDoesNotRecreateTheBootstrapSlugOnceTenantsExist() {
        TenantService tenantService = Application.getInstance(TenantService.class);
        TenantDefinition bootstrapped = tenantService.findBySlug("default").orElseThrow();

        // Renamed rather than deleted: the database behind this tenant is what the other test
        // classes work on. For ensureDefaultTenant() both are the same situation - no tenant
        // carries the bootstrap slug any more - and it used to recreate one on every start.
        rename(tenantService, bootstrapped.id(), "renamed-bootstrap-slug");
        try {
            int before = tenantService.listAll().size();

            tenantService.ensureDefaultTenant();

            assertThat(tenantService.findBySlug("default").isPresent(), is(false));
            assertThat(tenantService.listAll(), hasSize(before));
        } finally {
            rename(tenantService, bootstrapped.id(), "default");
        }
    }

    @Test
    void deleteWithCascadeClearsDefaultTenantSettingWhenSeveralTenantsRemain() {
        TenantService tenantService = Application.getInstance(TenantService.class);
        SettingsService settingsService = Application.getInstance(SettingsService.class);

        TenantDefinition doomed = tenantService.create("Default Cleanup Test", "default-cleanup-test");
        TenantDefinition other = tenantService.create("Default Cleanup Other", "default-cleanup-other");
        String previousDefault = settingsService.get(SettingKeys.DEFAULT_TENANT_ID, "");
        settingsService.set(SettingKeys.DEFAULT_TENANT_ID, doomed.id());

        try {
            tenantService.deleteWithCascade(doomed.id());

            assertThat(settingsService.get(SettingKeys.DEFAULT_TENANT_ID, null), is(emptyOrNullString()));
        } finally {
            settingsService.set(SettingKeys.DEFAULT_TENANT_ID, previousDefault);
            tenantService.deleteWithCascade(other.id());
        }
    }

    @Test
    void deleteWithCascadePromotesTheSoleRemainingTenantToDefault() {
        TenantService tenantService = Application.getInstance(TenantService.class);
        SettingsService settingsService = Application.getInstance(SettingsService.class);

        TenantDefinition doomed = tenantService.create("Promote Doomed", "promote-doomed-test");
        TenantDefinition survivor = tenantService.create("Promote Survivor", "promote-survivor-test");
        String previousDefault = settingsService.get(SettingKeys.DEFAULT_TENANT_ID, "");
        List<String> deactivated = deactivateAllActiveExcept(tenantService, doomed.id(), survivor.id());
        settingsService.set(SettingKeys.DEFAULT_TENANT_ID, doomed.id());

        try {
            tenantService.deleteWithCascade(doomed.id());

            assertThat(settingsService.get(SettingKeys.DEFAULT_TENANT_ID, null), is(survivor.id()));
        } finally {
            reactivate(tenantService, deactivated);
            settingsService.set(SettingKeys.DEFAULT_TENANT_ID, previousDefault);
            tenantService.deleteWithCascade(survivor.id());
        }
    }

    @Test
    void deleteWithCascadeLeavesUnrelatedDefaultTenantSettingUntouched() {
        TenantService tenantService = Application.getInstance(TenantService.class);
        SettingsService settingsService = Application.getInstance(SettingsService.class);

        TenantDefinition keepDefault = tenantService.create("Keep Default", "keep-default-test");
        TenantDefinition other = tenantService.create("Other Tenant", "other-tenant-test");
        String previousDefault = settingsService.get(SettingKeys.DEFAULT_TENANT_ID, "");
        settingsService.set(SettingKeys.DEFAULT_TENANT_ID, keepDefault.id());

        try {
            tenantService.deleteWithCascade(other.id());

            assertThat(settingsService.get(SettingKeys.DEFAULT_TENANT_ID, null), is(keepDefault.id()));
        } finally {
            settingsService.set(SettingKeys.DEFAULT_TENANT_ID, previousDefault);
            tenantService.deleteWithCascade(keepDefault.id());
        }
    }

    @Test
    void resolveDefaultTenantFallsBackToTheSoleActiveTenant() {
        TenantService tenantService = Application.getInstance(TenantService.class);
        SettingsService settingsService = Application.getInstance(SettingsService.class);

        TenantDefinition only = tenantService.create("Sole Active", "sole-active-test");
        String previousDefault = settingsService.get(SettingKeys.DEFAULT_TENANT_ID, "");
        List<String> deactivated = deactivateAllActiveExcept(tenantService, only.id());
        settingsService.set(SettingKeys.DEFAULT_TENANT_ID, "");

        try {
            assertThat(
                    tenantService.resolveDefaultTenant().map(TenantDefinition::id).orElse(null),
                    is(only.id()));
        } finally {
            reactivate(tenantService, deactivated);
            settingsService.set(SettingKeys.DEFAULT_TENANT_ID, previousDefault);
            tenantService.deleteWithCascade(only.id());
        }
    }

    @Test
    void resolveDefaultTenantStaysEmptyWhenSeveralTenantsAreActive() {
        TenantService tenantService = Application.getInstance(TenantService.class);
        SettingsService settingsService = Application.getInstance(SettingsService.class);

        TenantDefinition first = tenantService.create("Ambiguous One", "ambiguous-one-test");
        TenantDefinition second = tenantService.create("Ambiguous Two", "ambiguous-two-test");
        String previousDefault = settingsService.get(SettingKeys.DEFAULT_TENANT_ID, "");
        settingsService.set(SettingKeys.DEFAULT_TENANT_ID, "");

        try {
            assertThat(tenantService.resolveDefaultTenant().isPresent(), is(false));
        } finally {
            settingsService.set(SettingKeys.DEFAULT_TENANT_ID, previousDefault);
            tenantService.deleteWithCascade(first.id());
            tenantService.deleteWithCascade(second.id());
        }
    }

    private static void rename(TenantService tenantService, String id, String slug) {
        tenantService.update(id, null, slug, null, null, null, null, null, null, null, null);
    }

    /**
     * Switches off every active tenant except the given ones and returns what was switched off.
     * <p>
     * The cases around "exactly one active tenant" cannot be reached by deleting: the test
     * classes share one database, and the tenant the rest of them works on has to survive. The
     * caller puts the tenants back with {@link #reactivate(TenantService, List)} in a finally.
     */
    private static List<String> deactivateAllActiveExcept(TenantService tenantService, String... keep) {
        Set<String> kept = Set.of(keep);
        List<String> deactivated = new ArrayList<>();

        for (TenantDefinition tenant : tenantService.listAll()) {
            if (tenant.isActive() && !kept.contains(tenant.id())) {
                tenantService.update(
                        tenant.id(), null, null, STATUS_INACTIVE, null, null, null, null, null, null, null);
                deactivated.add(tenant.id());
            }
        }

        return deactivated;
    }

    private static void reactivate(TenantService tenantService, List<String> ids) {
        for (String id : ids) {
            tenantService.update(
                    id, null, null, TenantDefinition.STATUS_ACTIVE, null, null, null, null, null, null, null);
        }
    }
}
