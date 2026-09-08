package services;

import auth.TenantContext;
import constants.SettingKeys;
import io.mangoo.core.Application;
import models.TenantDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@ExtendWith(io.mangoo.test.TestRunner.class)
class TenantServiceTest {

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
    void deleteWithCascadeClearsDefaultTenantSettingWhenItPointedAtDeletedTenant() {
        TenantService tenantService = Application.getInstance(TenantService.class);
        SettingsService settingsService = Application.getInstance(SettingsService.class);

        TenantDefinition tenant = tenantService.create("Default Cleanup Test", "default-cleanup-test");
        settingsService.set(SettingKeys.DEFAULT_TENANT_ID, tenant.id());

        tenantService.deleteWithCascade(tenant.id());

        assertThat(settingsService.get(SettingKeys.DEFAULT_TENANT_ID, null), is(emptyOrNullString()));
    }

    @Test
    void deleteWithCascadeLeavesUnrelatedDefaultTenantSettingUntouched() {
        TenantService tenantService = Application.getInstance(TenantService.class);
        SettingsService settingsService = Application.getInstance(SettingsService.class);

        TenantDefinition keepDefault = tenantService.create("Keep Default", "keep-default-test");
        TenantDefinition other = tenantService.create("Other Tenant", "other-tenant-test");
        settingsService.set(SettingKeys.DEFAULT_TENANT_ID, keepDefault.id());

        tenantService.deleteWithCascade(other.id());

        assertThat(settingsService.get(SettingKeys.DEFAULT_TENANT_ID, null), is(keepDefault.id()));
    }
}
