package services;

import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import models.TenantDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The projected lookup of active tenants, which resolving the default tenant relies on: it has to
 * stay limited, carry what it promises, and leave out tenants that cannot be logged into.
 */
@ExtendWith({TestRunner.class})
class LoginTenantLookupTest {
    /** The lookup reads three fields, not whole tenant documents, and respects its limit. */
    @Test
    void theLookupIsLimitedAndCarriesOnlyItsThreeFields() {
        TenantService tenantService = Application.getInstance(TenantService.class);

        List<TenantService.TenantLookup> one = tenantService.findActiveForLookup(1);
        assertThat(one, hasSize(1));

        List<TenantService.TenantLookup> all = tenantService.findActiveForLookup(1000);
        assertThat(all.size(), greaterThanOrEqualTo(1));
        assertThat(all.stream().map(TenantService.TenantLookup::id).toList(), everyItem(notNullValue()));
        assertThat(all.stream().map(TenantService.TenantLookup::databaseName).toList(), everyItem(notNullValue()));
    }

    /** A suspended tenant cannot be logged into, so it is not part of the lookup either. */
    @Test
    void anInactiveTenantIsNotPartOfTheLookup() {
        TenantService tenantService = Application.getInstance(TenantService.class);
        TenantDefinition suspended = tenantService.findBySlug("lookup-suspended-tenant")
                .orElseGet(() -> tenantService.create("Lookup Suspended", "lookup-suspended-tenant"));
        tenantService.update(suspended.id(), null, null, "suspended",
                null, null, null, null, null, null, null, null);

        List<String> ids = tenantService.findActiveForLookup(1000).stream()
                .map(TenantService.TenantLookup::id)
                .toList();

        assertThat(ids, not(org.hamcrest.Matchers.hasItem(suspended.id())));
    }
}
