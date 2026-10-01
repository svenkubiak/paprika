package security;

import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.SystemUserService;
import services.TwoFactorService;
import utils.AdminTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Six-digit TOTP is only a second factor while guesses are limited. The budget must live on the
 * server: a counter in the pending-session JWT cookie could be replayed at zero.
 */
@ExtendWith({TestRunner.class})
class TwoFactorBruteForceIntegrationTest {

    @Test
    void lockTheSecondFactorAfterABudgetOfWrongCodes() {
        SystemUserService users = Application.getInstance(SystemUserService.class);
        String userId = superadminId();
        users.clearTwoFactorFailures(userId);

        Optional<java.time.Instant> lock = Optional.empty();
        int attempts = 0;
        while (lock.isEmpty() && attempts < 20) {
            lock = users.recordTwoFactorFailure(userId);
            attempts++;
        }

        assertThat("a run of wrong codes has to end in a lock", lock.isPresent(), is(true));
        assertThat("the lock must not arrive on the first wrong code either", attempts, greaterThan(1));
        assertThat(users.isTwoFactorLocked(userId), is(true));

        users.clearTwoFactorFailures(userId);
        assertThat("a correct code clears the budget", users.isTwoFactorLocked(userId), is(false));
    }

    @Test
    void lockIsAbsoluteAndNotPushedFurtherByMoreAttempts() {
        SystemUserService users = Application.getInstance(SystemUserService.class);
        String userId = superadminId();
        users.clearTwoFactorFailures(userId);

        java.time.Instant first = null;
        for (int i = 0; i < 20 && first == null; i++) {
            first = users.recordTwoFactorFailure(userId).orElse(null);
        }
        assertThat(first, notNullValue());

        // A guesser must not be able to extend the lockout of the rightful owner
        java.time.Instant afterMore = users.recordTwoFactorFailure(userId).orElseThrow();

        assertThat("a lock that renews itself would be a denial of service against the superadmin",
                afterMore, equalTo(first));

        users.clearTwoFactorFailures(userId);
    }

    @Test
    void theTwoFactorEndpointRefusesWhileLocked() {
        SystemUserService users = Application.getInstance(SystemUserService.class);
        String userId = superadminId();

        users.clearTwoFactorFailures(userId);
        for (int i = 0; i < 20 && users.recordTwoFactorFailure(userId).isEmpty(); i++) {
            // spend the budget
        }
        assertThat(users.isTwoFactorLocked(userId), is(true));

        try {
            TestResponse response = TestRequest.post("/api/admin/token/2fa")
                    .withStringBody("{\"code\":\"000000\"}")
                    .withContentType("application/json")
                    .execute();

            // A refusal before the code check is fine; a locked account must never get its code checked.
            assertThat("a locked second factor must not be answered with a token",
                    response.getStatusCode(), not(equalTo(200)));
            assertThat(response.getContent(), not(containsString("accessToken")));
        } finally {
            users.clearTwoFactorFailures(userId);
        }
    }

    @Test
    void theFallbackCodeStaysUsableWhileLocked() {
        SystemUserService users = Application.getInstance(SystemUserService.class);
        TwoFactorService twoFactor = Application.getInstance(TwoFactorService.class);
        String userId = superadminId();

        String secret = twoFactor.generateSecret();
        users.setTotpSecret(userId, secret);
        String fallback = users.generateTotpFallbackCode(userId);

        users.clearTwoFactorFailures(userId);
        for (int i = 0; i < 20 && users.recordTwoFactorFailure(userId).isEmpty(); i++) {
            // spend the budget
        }
        assertThat(users.isTwoFactorLocked(userId), is(true));

        try {
            // 32 random characters cannot be guessed, and the fallback is the way out of a guessing lockout.
            assertThat("the fallback code has to survive a lock",
                    users.consumeTotpFallbackCode(userId, fallback), is(true));
        } finally {
            users.clearTwoFactorFailures(userId);
            users.clearTotpSecret(userId);
        }
    }

    private static String superadminId() {
        AdminTestUtils.loginAsAdminWithDefaultTenant();
        List<Map<String, Object>> all = Application.getInstance(SystemUserService.class).listSuperadmins();
        assertThat("the test instance needs a superadmin", all, is(not(empty())));
        return String.valueOf(all.getFirst().get("id"));
    }
}
