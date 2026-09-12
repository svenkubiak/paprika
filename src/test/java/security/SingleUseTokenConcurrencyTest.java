package security;

import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import models.TenantDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.SystemUserService;
import services.TenantService;
import services.TenantUserService;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Single use tokens have to stay single use under concurrency.
 * <p>
 * "Look the token up, then clear it" reads correctly in sequence but is two operations: several
 * requests arriving at the same time all pass the check before any of them clears the token. A
 * password reset link that was forwarded, logged by a mail gateway or captured in a browser history
 * could then be redeemed more than once - and a leaked 2FA fallback code would stop being one-time.
 * <p>
 * These tests fire the redemptions in parallel and require that exactly one succeeds. They are
 * written so that a regression is visible rather than flaky: the requests are released together by
 * a latch, and the assertion is on the number of successes, not on timing.
 * <p>
 * The rejected attempts are required to answer with a client error: concurrency must not turn into
 * a server error either. Up to mangoo I/O 10.12.1 that was not the case - its attachment key was
 * built lazily on an unsynchronized static field, so parallel requests sporadically produced a 500.
 * Fixed in 10.12.2, which is why this can be asserted strictly now.
 */
@ExtendWith({TestRunner.class})
class SingleUseTokenConcurrencyTest {
    private static final int PARALLEL_ATTEMPTS = 8;
    private static final String PASSWORD = "concurrency-password-aaa-1";

    private static TenantDefinition tenant;

    @BeforeAll
    static void setUp() {
        tenant = TenantTestUtils.defaultTenant();
        Application.getInstance(TenantService.class).update(
                tenant.id(), null, null, null, null, true, true, null,
                "https://app.example.test/reset", "https://app.example.test/verify", null);
        tenant = TenantTestUtils.defaultTenant();
    }

    @Test
    void aPasswordResetTokenCanOnlyBeRedeemedOnce() throws Exception {
        String username = "concurrent-reset-" + DbUtils.id().substring(0, 8);
        TenantUserService users = Application.getInstance(TenantUserService.class);
        users.createUser(tenant, username, username + "@example.test", PASSWORD);

        String token = users.issuePasswordResetToken(tenant, username + "@example.test")
                .orElseThrow()
                .token();

        List<Integer> statuses = inParallel(index -> TestRequest.post("/api/auth/password/reset")
                .withStringBody("{\"tenant\":\"" + tenant.slug() + "\",\"token\":\"" + token
                        + "\",\"password\":\"concurrent-new-password-" + index + "\"}")
                .withContentType("application/json")
                .execute()
                .getStatusCode());

        assertThat("exactly one redemption may succeed: " + statuses, count(statuses, 200), equalTo(1));
        assertThat("every rejection must be a client error, never a server error: " + statuses,
                statuses.stream().filter(status -> status >= 500).count(), equalTo(0L));
        assertThat("and every other attempt must be rejected: " + statuses,
                count(statuses, 400), equalTo(PARALLEL_ATTEMPTS - 1));

        // Exactly one of the attempted passwords is now valid, and the old one is gone
        assertThat(login(username, PASSWORD).getStatusCode(), equalTo(401));
        long usable = 0;
        for (int index = 0; index < PARALLEL_ATTEMPTS; index++) {
            if (login(username, "concurrent-new-password-" + index).getStatusCode() == 200) {
                usable++;
            }
        }
        assertThat("the account must end up with one well defined password", usable, equalTo(1L));
    }

    @Test
    void anEmailVerificationTokenCanOnlyBeRedeemedOnce() throws Exception {
        String username = "concurrent-verify-" + DbUtils.id().substring(0, 8);
        TenantUserService users = Application.getInstance(TenantUserService.class);
        users.createUser(tenant, username, username + "@example.test", PASSWORD);

        String token = users.issueEmailVerificationToken(tenant, username + "@example.test")
                .orElseThrow()
                .token();

        List<Integer> statuses = inParallel(index -> TestRequest.post("/api/auth/verify/confirm")
                .withStringBody("{\"tenant\":\"" + tenant.slug() + "\",\"token\":\"" + token + "\"}")
                .withContentType("application/json")
                .execute()
                .getStatusCode());

        assertThat("exactly one confirmation may succeed: " + statuses, count(statuses, 200), equalTo(1));
        assertThat("every rejection must be a client error, never a server error: " + statuses,
                statuses.stream().filter(status -> status >= 500).count(), equalTo(0L));
        assertThat("and every other attempt must be rejected: " + statuses,
                count(statuses, 400), equalTo(PARALLEL_ATTEMPTS - 1));
    }

    @Test
    void aTwoFactorFallbackCodeCanOnlyBeConsumedOnce() throws Exception {
        SystemUserService systemUsers = Application.getInstance(SystemUserService.class);
        String username = "concurrent-admin-" + DbUtils.id().substring(0, 8);
        // A pending invite is enough: the fallback code lives on the user record either way, and
        // this keeps the "last completed superadmin" invariant other tests rely on untouched
        systemUsers.createSuperadminSetup(username, null);
        String adminId = String.valueOf(systemUsers.findPublicUserByUsername(username).orElseThrow().get("id"));
        systemUsers.setTotpSecret(adminId, "CONCURRENCYFALLBACKSECRET");
        String fallbackCode = systemUsers.generateTotpFallbackCode(adminId);

        AtomicInteger consumed = new AtomicInteger();
        String userId = adminId;
        inParallel(index -> {
            if (systemUsers.consumeTotpFallbackCode(userId, fallbackCode)) {
                consumed.incrementAndGet();
            }
            return 0;
        });

        assertThat("a fallback code must be usable exactly once", consumed.get(), equalTo(1));
        assertThat("and never again afterwards",
                systemUsers.consumeTotpFallbackCode(adminId, fallbackCode), is(false));
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** Releases all attempts at once, so they genuinely overlap instead of running in sequence. */
    private static List<Integer> inParallel(IndexedCall call) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_ATTEMPTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < PARALLEL_ATTEMPTS; index++) {
                int current = index;
                Callable<Integer> task = () -> {
                    start.await();
                    return call.apply(current);
                };
                futures.add(executor.submit(task));
            }

            start.countDown();

            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static int count(List<Integer> statuses, int status) {
        return (int) statuses.stream().filter(value -> value == status).count();
    }

    private static TestResponse login(String username, String password) {
        return TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(tenant.slug(), username, password))
                .withContentType("application/json")
                .execute();
    }

    @FunctionalInterface
    private interface IndexedCall {
        Integer apply(int index) throws Exception;
    }
}
