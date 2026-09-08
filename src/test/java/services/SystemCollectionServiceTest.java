package services;

import io.mangoo.core.Application;
import models.TenantDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import constants.CollectionName;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(io.mangoo.test.TestRunner.class)
class SystemCollectionServiceTest {

    @Test
    void ensuresSystemCollectionsOnStartup() {
        TenantService tenantService = Application.getInstance(TenantService.class);
        SystemUserService systemUserService = Application.getInstance(SystemUserService.class);
        TenantDatabaseResolver resolver = Application.getInstance(TenantDatabaseResolver.class);

        TenantDefinition defaultTenant = tenantService.findBySlug("default").orElse(null);
        assertThat(defaultTenant, notNullValue());
        assertThat(defaultTenant.databaseName().startsWith("tenant_"), is(true));

        assertThat(systemUserService.findPublicUserByUsername("admin").orElse(null), notNullValue());
        assertThrows(
                IllegalArgumentException.class,
                () -> systemUserService.createSuperadmin(
                        "short-password-test",
                        null,
                        "123456789012345"));

        var tenantMeta = resolver.tenantDatabase(defaultTenant.databaseName())
                .getCollection(CollectionName.META_COLLECTIONS, models.CollectionDefinition.class)
                .find(com.mongodb.client.model.Filters.eq("name", "users"))
                .first();

        assertThat(tenantMeta, notNullValue());
        assertThat(tenantMeta.isSystem(), is(true));

        var fieldNames = tenantMeta.fields().stream().map(models.FieldDefinition::name).toList();
        assertThat(fieldNames.size(), is(4));
        assertThat(fieldNames.contains("username"), is(true));
        assertThat(fieldNames.contains("email"), is(true));
        assertThat(fieldNames.contains("role"), is(true));
        assertThat(fieldNames.contains("password"), is(true));
        assertThat(fieldNames.contains("passwordHash"), is(false));
        assertThat(fieldNames.contains("passwordSalt"), is(false));
    }
}
