package services;

import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import models.TenantDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import results.TenantLoginResult;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * A login without a tenant slug costs one database query per tenant, which makes a single
 * unauthenticated request more expensive the more customers the instance has. A rate limiter in
 * front of it counts requests, not what they cost, so the ceiling has to be here.
 */
@ExtendWith({TestRunner.class})
class LoginTenantLookupTest {
    private static final String PASSWORD = "lookup-password-123";
    private static final String USERNAME = "lookup-scan-user-" + utils.DbUtils.id();

    private static TenantDefinition tenant;

    @BeforeAll
    static void seed() {
        TenantService tenantService = Application.getInstance(TenantService.class);
        tenant = tenantService.findBySlug("lookup-tenant")
                .orElseGet(() -> tenantService.create("Lookup Tenant", "lookup-tenant"));

        Application.getInstance(TenantUserService.class).createUser(tenant, USERNAME, null, PASSWORD);
    }

    /** The lookup reads three fields, not whole tenant documents, and respects its limit. */
    @Test
    void theLookupIsLimitedAndCarriesOnlyWhatTheScanNeeds() {
        TenantService tenantService = Application.getInstance(TenantService.class);

        List<TenantService.TenantLookup> one = tenantService.findActiveForLookup(1);
        assertThat(one, hasSize(1));

        List<TenantService.TenantLookup> all = tenantService.findActiveForLookup(1000);
        assertThat(all.size(), greaterThanOrEqualTo(1));
        assertThat(all.stream().map(TenantService.TenantLookup::id).toList(), everyItem(notNullValue()));
        assertThat(all.stream().map(TenantService.TenantLookup::databaseName).toList(), everyItem(notNullValue()));
    }

    /** A suspended tenant cannot be logged into, so it must not be queried either. */
    @Test
    void anInactiveTenantIsNotPartOfTheScan() {
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

    /** Under the cap the convenience works: the username is found in its one tenant. */
    @Test
    void aUsernameIsResolvedWhileTheInstanceStaysUnderTheCap() {
        TenantUserService users = withCandidates(candidates(TenantUserService.MAX_SCANNED_TENANTS, true));

        TenantLoginResult result = users.authenticateForLogin(USERNAME, PASSWORD, null);

        assertThat(result.status(), equalTo(TenantLoginResult.Status.SUCCESS));
    }

    /**
     * Past the cap the slug-less login is refused - and refused the same way every other failed
     * login is, so it does not become a signal of its own.
     */
    @Test
    void aboveTheCapTheScanIsRefusedInsteadOfQueryingEveryTenant() {
        TenantUserService users = withCandidates(candidates(TenantUserService.MAX_SCANNED_TENANTS + 1, true));

        TenantLoginResult result = users.authenticateForLogin(USERNAME, PASSWORD, null);

        assertThat("a correct password must not get through a scan that was refused",
                result.status(), equalTo(TenantLoginResult.Status.INVALID_CREDENTIALS));
        assertThat(result.auth().isPresent(), is(false));
    }

    /** Naming the tenant bypasses the scan entirely, so the cap cannot lock anyone out. */
    @Test
    void namingTheTenantStillWorksAboveTheCap() {
        TenantUserService users = withCandidates(candidates(TenantUserService.MAX_SCANNED_TENANTS + 1, true));

        TenantLoginResult result = users.authenticateForLogin(USERNAME, PASSWORD, tenant.slug());

        assertThat(result.status(), equalTo(TenantLoginResult.Status.SUCCESS));
    }

    /**
     * {@code count} lookup entries: the real tenant plus fillers that point at databases which
     * do not exist, so the username is found exactly once and the scan still has to walk them
     * all to know that.
     */
    private static List<TenantService.TenantLookup> candidates(int count, boolean includeReal) {
        List<TenantService.TenantLookup> candidates = new ArrayList<>();
        if (includeReal) {
            candidates.add(new TenantService.TenantLookup(tenant.id(), tenant.slug(), tenant.databaseName()));
        }
        while (candidates.size() < count) {
            int index = candidates.size();
            candidates.add(new TenantService.TenantLookup(
                    "lookup-filler-" + index, "lookup-filler-" + index, "tenant_lookup_filler_" + index));
        }
        return candidates;
    }

    /**
     * The real service with its real dependencies, only the set of tenants a slug-less login
     * would search is dictated by the test - creating two dozen tenant databases to reach the
     * cap would leave them behind for every other test in the run.
     */
    private static TenantUserService withCandidates(List<TenantService.TenantLookup> candidates) {
        return new TenantUserService(
                Application.getInstance(TenantDatabaseResolver.class),
                Application.getInstance(TenantService.class),
                Application.getInstance(RealtimeService.class),
                Application.getInstance(TenantCollectionService.class),
                Application.getInstance(ValidationService.class),
                Application.getInstance(PasswordHashGate.class)) {
            @Override
            List<TenantService.TenantLookup> activeTenantsForLookup() {
                return candidates;
            }
        };
    }
}
