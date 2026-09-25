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
 * Executable specification of the attempt budget on the superadmin's second factor.
 * <p>
 * A TOTP code is six digits: one in a million per 30 second window. That is only a second factor
 * for as long as something limits how often it may be guessed. Nothing did - a wrong code left the
 * pending 2FA session untouched, so whoever held a password could keep guessing against the same
 * session indefinitely, and at a few attempts per second the odds pass 50% within two days. Behind
 * that code sits the one identity that can export every tenant's database.
 * <p>
 * The budget has to live on the server: the pending session is a JWT in a cookie, so a counter
 * kept in it would simply be replayed at zero by resending the earlier cookie.
 */
@ExtendWith({TestRunner.class})
class TwoFactorBruteForceIntegrationTest {

    @Test
    void lockTheSecondFactorAfterABudgetOfWrongCodes() {
        SystemUserService users = Application.getInstance(SystemUserService.class);
        String userId = superadminId();
        users.clearTwoFactorFailures(userId);

        // Budget is spent without ever locking
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

        // Whoever keeps guessing must not be able to keep the rightful owner out any longer
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

            // Without a pending session the request is refused earlier, which is also a refusal -
            // what must never happen is that a locked account still gets its code checked.
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
            // 32 random characters cannot be guessed, so keeping it usable costs nothing - and it
            // is the way out when someone locks the account by guessing at it.
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
