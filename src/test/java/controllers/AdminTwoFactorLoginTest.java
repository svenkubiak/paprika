package controllers;

import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.mangoo.utils.TotpUtils;
import io.undertow.util.StatusCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.SystemUserService;
import services.TwoFactorService;
import utils.AdminTestUtils;
import utils.DbUtils;

import java.net.HttpCookie;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * The second factor is a separate step: the password only opens a pending session, and the code
 * has to be presented on that session. A fallback code is the one way through a lock.
 */
@ExtendWith({TestRunner.class})
class AdminTwoFactorLoginTest {
    private static final String PASSWORD = "two-factor-login-password-1";
    private static final String SESSION = "paprika-session";
    private static final String AUTH = "paprika-authentication";

    @Test
    void aCodeOnThePendingSessionSignsInAndCannotBeReplayed() {
        String username = "login-2fa-" + DbUtils.id().substring(0, 8);
        String adminId = create(username);
        try {
            String secret = enableTotp(adminId);

            TestResponse noSession = post("/api/admin/login/2fa", null, "{\"code\":\"000000\"}");
            assertThat(noSession.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
            assertThat(noSession.getContent(), containsString("No pending sign-in"));

            TestResponse pending = login(username);
            assertThat(pending.getContent(), containsString("requiresTwoFactor"));
            assertThat(pending.getCookie(AUTH), nullValue());

            String code = TotpUtils.getTotp(secret);
            TestResponse confirmed = post("/api/admin/login/2fa", pending.getCookie(SESSION),
                    "{\"code\":\"" + code + "\"}");
            assertThat(confirmed.getContent(), confirmed.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(confirmed.getContent(), containsString("success"));
            assertThat(confirmed.getCookie(AUTH), notNullValue());

            TestResponse again = login(username);
            TestResponse replayed = post("/api/admin/login/2fa", again.getCookie(SESSION),
                    "{\"code\":\"" + code + "\"}");
            assertThat(replayed.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
            assertThat(replayed.getContent(), containsString("Invalid verification code"));
        } finally {
            AdminTestUtils.removeSuperadmin(adminId);
        }
    }

    @Test
    void aTokenWaitsForTheCodeAndTheFactorCanBeTurnedOff() {
        String tokenUser = "login-2fa-token-" + DbUtils.id().substring(0, 8);
        String tokenUserId = create(tokenUser);
        String profileUser = "login-2fa-off-" + DbUtils.id().substring(0, 8);
        String profileUserId = create(profileUser);
        try {
            String secret = enableTotp(tokenUserId);
            TestResponse tokenPending = post("/api/admin/token", null, credentials(tokenUser));
            assertThat(tokenPending.getContent(), containsString("requiresTwoFactor"));
            TestResponse token = post("/api/admin/token/2fa", tokenPending.getCookie(SESSION),
                    "{\"code\":\"" + TotpUtils.getTotp(secret) + "\"}");
            assertThat(token.getContent(), token.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(token.getContent(), containsString("accessToken"));

            HttpCookie signedIn = login(profileUser).getCookie(AUTH);
            assertThat(signedIn, notNullValue());
            String profileSecret = enableTotp(profileUserId);

            TestResponse missingCode = post("/api/admin/profile/2fa/disable", signedIn,
                    "{\"password\":\"" + PASSWORD + "\"}");
            assertThat(missingCode.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
            assertThat(missingCode.getContent(), containsString("Verification code is required"));

            TestResponse disabled = post("/api/admin/profile/2fa/disable", signedIn,
                    "{\"password\":\"" + PASSWORD + "\",\"code\":\"" + TotpUtils.getTotp(profileSecret) + "\"}");
            assertThat(disabled.getContent(), disabled.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(disabled.getContent(), containsString("\"twoFactorEnabled\":false"));

            TestResponse afterwards = login(profileUser);
            assertThat(afterwards.getContent(), containsString("success"));
            assertThat(afterwards.getCookie(AUTH), notNullValue());
        } finally {
            AdminTestUtils.removeSuperadmin(tokenUserId);
            AdminTestUtils.removeSuperadmin(profileUserId);
        }
    }

    @Test
    void totpLocksAfterRepeatedFailuresAndTheFallbackCodeStillSignsInOnce() {
        String username = "login-2fa-lock-" + DbUtils.id().substring(0, 8);
        String adminId = create(username);
        try {
            enableTotp(adminId);
            String fallback = Application.getInstance(SystemUserService.class).generateTotpFallbackCode(adminId);
            HttpCookie session = login(username).getCookie(SESSION);

            for (int attempt = 1; attempt <= 4; attempt++) {
                TestResponse rejected = post("/api/admin/login/2fa", session, "{\"code\":\"000000\"}");
                assertThat(rejected.getContent(), rejected.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
            }

            TestResponse locked = post("/api/admin/login/2fa", session, "{\"code\":\"000000\"}");
            assertThat(locked.getStatusCode(), equalTo(StatusCodes.TOO_MANY_REQUESTS));
            assertThat(locked.getContent(), containsString("locked"));
            assertThat(locked.getHeader("Retry-After"), notNullValue());

            TestResponse recovered = post("/api/admin/login/2fa", session, "{\"code\":\"" + fallback + "\"}");
            assertThat(recovered.getContent(), recovered.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(recovered.getCookie(AUTH), notNullValue());

            HttpCookie again = login(username).getCookie(SESSION);
            TestResponse reused = post("/api/admin/login/2fa", again, "{\"code\":\"" + fallback + "\"}");
            assertThat(reused.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
            assertThat(reused.getContent(), containsString("Invalid verification code"));
        } finally {
            AdminTestUtils.removeSuperadmin(adminId);
        }
    }

    private static String create(String username) {
        return String.valueOf(Application.getInstance(SystemUserService.class)
                .createSuperadmin(username, null, PASSWORD).get("id"));
    }

    private static String enableTotp(String adminId) {
        String secret = Application.getInstance(TwoFactorService.class).generateSecret();
        Application.getInstance(SystemUserService.class).setTotpSecret(adminId, secret);
        return secret;
    }

    private static TestResponse login(String username) {
        return post("/api/admin/login", null, credentials(username));
    }

    private static String credentials(String username) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    private static TestResponse post(String uri, HttpCookie cookie, String body) {
        TestResponse request = TestRequest.post(uri).withStringBody(body).withContentType("application/json");
        if (cookie != null) {
            request = request.withCookie(cookie);
        }
        return request.execute();
    }
}
