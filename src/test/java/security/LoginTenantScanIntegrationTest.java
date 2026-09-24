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
 * {@code POST /api/auth/login} may be called without a tenant slug, in which case the server has
 * to work out which tenant the username belongs to. Everything that answer reveals is
 * cross-tenant information the isolation elsewhere is careful never to leak, so the endpoint has
 * to behave like the recovery endpoints already do: one answer for every kind of failure.
 */
@ExtendWith({TestRunner.class})
class LoginTenantScanIntegrationTest {
    private static final String PASSWORD_A = "scan-password-aaa-1";
    private static final String PASSWORD_B = "scan-password-bbb-2";
    private static final String SHARED_USERNAME = "scan-shared-user";
    private static final String UNIQUE_USERNAME = "scan-unique-user";

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
    }

    /**
     * The oracle: a 409 tells an unauthenticated caller that this username exists in more than
     * one tenant. Nothing about another tenant's user base may be derivable from here.
     */
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

    /** Naming the tenant resolves the ambiguity - that is what the slug is for. */
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

        // ... and the password of the other tenant's namesake does not work
        TestResponse crossed = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(tenantA.slug(), SHARED_USERNAME, PASSWORD_B))
                .withContentType("application/json")
                .execute();
        assertThat(crossed.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
    }

    /** The convenience path keeps working where the username really is unique. */
    @Test
    void aUniqueUsernameStillLogsInWithoutASlug() {
        TestResponse response = login(UNIQUE_USERNAME, PASSWORD_A);

        assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), org.hamcrest.Matchers.containsString("accessToken"));
    }

    /**
     * Argon2 only ran when a user was found, so an unknown username came back an order of
     * magnitude faster than a known one with a wrong password - the same enumeration the status
     * code no longer gives away. The bound is deliberately loose; the point is the order of
     * magnitude, not a precise ratio.
     */
    @Test
    void anUnknownUsernameCostsRoughlyAsMuchAsAKnownOne() {
        // Warm up the hashing code, so the first call does not skew the comparison
        login(UNIQUE_USERNAME, "warm-up-password");

        long known = median(() -> login(UNIQUE_USERNAME, "wrong-password-for-a-known-user"));
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
