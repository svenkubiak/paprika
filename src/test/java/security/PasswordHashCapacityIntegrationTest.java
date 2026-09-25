package security;

import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.PasswordHashGate;
import services.UserService;
import utils.AdminTestUtils;
import utils.TenantTestUtils;

import java.net.HttpCookie;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Executable specification of the ceiling on concurrent password hashing.
 * <p>
 * Every login runs one Argon2id verification - about 90 MiB of heap for a quarter of a second -
 * and it runs for an unknown username too, because a hash that always happens is what keeps the
 * response time from giving account existence away. That makes an ~80 byte request a ~90 MiB
 * allocation, so the number of them running at once has to be bounded inside the application: a
 * rate limiter in front of it counts requests, not what they cost, and its burst is exactly how
 * many expensive computations arrive together.
 * <p>
 * What is asserted here is that an instance at capacity refuses with 429 instead of allocating,
 * that the refusal looks identical for a known and an unknown username, and that the gate opens
 * again afterwards.
 */
@ExtendWith({TestRunner.class})
class PasswordHashCapacityIntegrationTest {
    private static final String USERNAME = "hash-capacity-user";
    private static final String PASSWORD = "hash-capacity-password-1";

    @Test
    void refusesLoginsWithoutHashingWhenAtCapacity() throws Exception {
        ensureUser();
        try (HeldPermits held = HeldPermits.acquireAll()) {
            TestResponse response = login(USERNAME, PASSWORD);

            assertThat("a login that cannot get a hashing permit must be refused, not queued",
                    response.getStatusCode(), equalTo(429));
            assertThat(response.getContent(), containsString("Too many authentication requests"));
            assertThat("a refusal has to tell the client when to come back",
                    response.getHeader("Retry-After"), notNullValue());
        }
    }

    @Test
    void refusalDoesNotRevealWhetherTheAccountExists() throws Exception {
        ensureUser();
        try (HeldPermits held = HeldPermits.acquireAll()) {
            TestResponse known = login(USERNAME, "the-wrong-password-entirely");
            TestResponse unknown = login("hash-capacity-nobody", "the-wrong-password-entirely");

            assertThat("the gate is taken before the username is looked at, so both answer alike",
                    known.getStatusCode(), equalTo(unknown.getStatusCode()));
            assertThat(known.getStatusCode(), equalTo(429));
            assertThat(known.getContent(), equalTo(unknown.getContent()));
        }
    }

    @Test
    void servesLoginsAgainOnceThePermitsAreBack() throws Exception {
        ensureUser();

        try (HeldPermits held = HeldPermits.acquireAll()) {
            assertThat(login(USERNAME, PASSWORD).getStatusCode(), equalTo(429));
        }

        TestResponse response = login(USERNAME, PASSWORD);
        assertThat("the gate must not stay shut after the permits were released",
                response.getStatusCode(), equalTo(200));
        assertThat(response.getContent(), containsString("accessToken"));
    }

    @Test
    void capIsDerivedFromTheHeapAndStaysWithinItsBounds() {
        PasswordHashGate gate = Application.getInstance(PasswordHashGate.class);

        assertThat("at least two, so one slow login cannot block the instance",
                gate.permits(), greaterThanOrEqualTo(2));
        assertThat("at most eight, beyond which more parallelism buys no throughput",
                gate.permits(), lessThanOrEqualTo(8));

        long peak = (long) gate.permits() * PasswordHashGate.BYTES_PER_HASH;
        assertThat("the worst case has to stay inside the heap this JVM was given",
                peak, lessThan(Runtime.getRuntime().maxMemory()));
    }

    @Test
    void refusesSuperadminLoginsWithoutHashingWhenAtCapacity() throws Exception {
        // The superadmin login runs the same Argon2id verification as the tenant login and holds
        // the same ~90 MiB while it does. It sits behind the admin IP gate, which makes it harder
        // to reach but no cheaper to serve - the ceiling is on what this instance allocates, not
        // on who asked for it.
        String password = AdminTestUtils.prepareAdminPassword();

        try (HeldPermits held = HeldPermits.acquireAll()) {
            TestResponse response = adminLogin(password);

            assertThat("a superadmin login that cannot get a permit must be refused, not queued",
                    response.getStatusCode(), equalTo(429));
            assertThat(response.getContent(), containsString("Too many authentication requests"));
            assertThat("a refusal has to tell the client when to come back",
                    response.getHeader("Retry-After"), notNullValue());
            assertThat("and it is not a sign-in",
                    response.getCookie("paprika-authentication"), nullValue());
        }

        assertThat("the gate must open again for the superadmin too",
                adminLogin(password).getStatusCode(), equalTo(200));
    }

    @Test
    void refusalIsNotReportedAsAWrongPassword() throws Exception {
        // The distinction the result type exists for: at capacity the password was never looked
        // at, so answering 401 would tell the rightful owner their password is wrong and would
        // tell an attacker their guess failed when it was never checked.
        String password = AdminTestUtils.prepareAdminPassword();

        try (HeldPermits held = HeldPermits.acquireAll()) {
            TestResponse right = adminLogin(password);
            TestResponse wrong = adminLogin("the-wrong-password-entirely");

            assertThat(right.getStatusCode(), equalTo(429));
            assertThat("right and wrong are indistinguishable while the instance is full",
                    wrong.getStatusCode(), equalTo(right.getStatusCode()));
            assertThat(wrong.getContent(), equalTo(right.getContent()));
        }
    }

    @Test
    void theProfilePasswordCheckObeysTheSameCap() throws Exception {
        // Re-entering the password to change it is authenticated, but it allocates exactly as
        // much as a login does. A cap that only covered the unauthenticated paths would not be
        // a cap on what the instance can be made to allocate.
        String password = AdminTestUtils.prepareAdminPassword();
        HttpCookie authentication = AdminTestUtils.loginAsAdmin();

        try (HeldPermits held = HeldPermits.acquireAll()) {
            TestResponse response = TestRequest.post("/api/admin/profile/password")
                    .withCookie(authentication)
                    .withStringBody("{\"currentPassword\":\"" + password
                            + "\",\"newPassword\":\"a-brand-new-password-1\"}")
                    .withContentType("application/json")
                    .execute();

            assertThat(response.getStatusCode(), equalTo(429));
            assertThat("a refusal must not read as a wrong current password",
                    response.getContent(), containsString("Too many authentication requests"));
        }

        assertThat("and the password is unchanged, because the check never ran",
                adminLogin(password).getStatusCode(), equalTo(200));
    }

    // ---------------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------------

    /** Holds every permit of the shared gate until closed, so the instance is provably full. */
    private static final class HeldPermits implements AutoCloseable {
        private final CountDownLatch release = new CountDownLatch(1);
        private final List<Thread> holders = new ArrayList<>();

        static HeldPermits acquireAll() throws InterruptedException {
            PasswordHashGate gate = Application.getInstance(PasswordHashGate.class);
            HeldPermits held = new HeldPermits();
            CountDownLatch acquired = new CountDownLatch(gate.permits());

            for (int i = 0; i < gate.permits(); i++) {
                Thread holder = new Thread(() -> gate.withPermit(() -> {
                    acquired.countDown();
                    try {
                        held.release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return Boolean.TRUE;
                }));
                holder.setDaemon(true);
                holder.start();
                held.holders.add(holder);
            }

            if (!acquired.await(30, TimeUnit.SECONDS)) {
                held.close();
                throw new IllegalStateException("Could not take all hashing permits");
            }
            return held;
        }

        @Override
        public void close() {
            release.countDown();
            for (Thread holder : holders) {
                try {
                    holder.join(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static void ensureUser() {
        UserService users = Application.getInstance(UserService.class);
        try {
            users.createUser(USERNAME, null, PASSWORD);
        } catch (IllegalArgumentException e) {
            // Already created by an earlier test in this run - the suites share one database
        }
    }

    private static TestResponse login(String username, String password) {
        return TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(username, password))
                .withContentType("application/json")
                .execute();
    }

    private static TestResponse adminLogin(String password) {
        return TestRequest.post("/api/admin/login")
                .withStringBody("{\"username\":\"admin\",\"password\":\"" + password + "\"}")
                .withContentType("application/json")
                .execute();
    }
}
