package security;

import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.TenantDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantService;
import services.TenantUserService;
import utils.TenantTestUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * A slug-less login is a login for the default tenant only; scanning all tenants ran foreign hooks
 * and leaked statements about other tenants' user bases.
 */
@ExtendWith({TestRunner.class})
class LoginTenantScanIntegrationTest {
    private static final String PASSWORD_A = "scan-password-aaa-1";
    private static final String PASSWORD_B = "scan-password-bbb-2";
    private static final String SHARED_USERNAME = "scan-shared-user";
    private static final String UNIQUE_USERNAME = "scan-unique-user";
    private static final String DEFAULT_USERNAME = "scan-default-user-" + utils.DbUtils.id();
    private static final String PASSWORD_DEFAULT = "scan-password-ddd-4";

    private static TenantDefinition tenantA;
    private static TenantDefinition tenantB;

    @BeforeAll
    static void seed() {
        TenantService tenantService = Application.getInstance(TenantService.class);
        TenantUserService users = Application.getInstance(TenantUserService.class);

        tenantA = tenantService.findBySlug("scan-tenant-a")
                .orElseGet(() -> tenantService.create("Scan Tenant A", "scan-tenant-a"));
        tenantB = tenantService.findBySlug("scan-tenant-b")
                .orElseGet(() -> tenantService.create("Scan Tenant B", "scan-tenant-b"));

        users.createUser(tenantA, SHARED_USERNAME, null, PASSWORD_A);
        users.createUser(tenantB, SHARED_USERNAME, null, PASSWORD_B);
        users.createUser(tenantA, UNIQUE_USERNAME, null, PASSWORD_A);
        users.createUser(TenantTestUtils.defaultTenant(), DEFAULT_USERNAME, null, PASSWORD_DEFAULT);
    }

    /** A 409 would tell an unauthenticated caller the username exists in more than one tenant. */
    @Test
    void anAmbiguousUsernameIsAnsweredLikeAnyOtherFailedLogin() {
        TestResponse ambiguous = login(SHARED_USERNAME, PASSWORD_A);
        TestResponse unknown = login("scan-user-that-does-not-exist", PASSWORD_A);

        assertThat(ambiguous.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat("an ambiguous username must not be distinguishable from an unknown one",
                ambiguous.getContent(), equalTo(unknown.getContent()));
        assertThat(ambiguous.getContent(), not(org.hamcrest.Matchers.containsString("Ambiguous")));
        assertThat(ambiguous.getContent(), not(org.hamcrest.Matchers.containsString("tenant")));
    }

    @Test
    void theSameUsernameStillLogsInWhenTheTenantIsNamed() {
        TestResponse inA = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(tenantA.slug(), SHARED_USERNAME, PASSWORD_A))
                .withContentType("application/json")
                .execute();
        assertThat(inA.getContent(), inA.getStatusCode(), equalTo(StatusCodes.OK));

        TestResponse inB = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(tenantB.slug(), SHARED_USERNAME, PASSWORD_B))
                .withContentType("application/json")
                .execute();
        assertThat(inB.getContent(), inB.getStatusCode(), equalTo(StatusCodes.OK));

        TestResponse crossed = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(tenantA.slug(), SHARED_USERNAME, PASSWORD_B))
                .withContentType("application/json")
                .execute();
        assertThat(crossed.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
    }

    @Test
    void aUserOfAnotherTenantHasToNameIt() {
        TestResponse withoutSlug = login(UNIQUE_USERNAME, PASSWORD_A);
        TestResponse unknown = login("scan-user-that-does-not-exist", PASSWORD_A);

        assertThat(withoutSlug.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat("a user of another tenant must not be distinguishable from an unknown one",
                withoutSlug.getContent(), equalTo(unknown.getContent()));
    }

    @Test
    void aUserOfTheDefaultTenantStillLogsInWithoutASlug() {
        TestResponse response = login(DEFAULT_USERNAME, PASSWORD_DEFAULT);

        assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), org.hamcrest.Matchers.containsString("accessToken"));
    }

    /**
     * Skipping Argon2 for unknown users makes them an order of magnitude faster, an enumeration oracle.
     * The bound is deliberately loose; only the order of magnitude matters.
     */
    @Test
    void anUnknownUsernameCostsRoughlyAsMuchAsAKnownOne() {
        // Warm up the hashing code, so the first call does not skew the comparison
        login(DEFAULT_USERNAME, "warm-up-password");

        long known = median(() -> login(DEFAULT_USERNAME, "wrong-password-for-a-known-user"));
        long unknown = median(() -> login("scan-user-that-does-not-exist-either", "wrong-password"));

        assertThat("the fixture is too fast to measure anything", known, greaterThan(0L));
        assertThat("an unknown username answered in " + unknown + " ms against " + known
                        + " ms for a known one - that difference is a user enumeration oracle",
                unknown * 3 > known, is(true));
    }

    private static long median(Runnable call) {
        long[] samples = new long[3];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            call.run();
            samples[i] = (System.nanoTime() - start) / 1_000_000;
        }
        java.util.Arrays.sort(samples);
        return samples[samples.length / 2];
    }

    private static TestResponse login(String username, String password) {
        return TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(username, password))
                .withContentType("application/json")
                .execute();
    }
}
