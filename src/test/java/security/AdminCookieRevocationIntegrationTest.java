package security;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.mangoo.utils.JsonUtils;
import io.mangoo.utils.TotpUtils;
import io.undertow.util.StatusCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.SystemUserService;
import utils.AdminTestUtils;
import utils.DbUtils;

import java.net.HttpCookie;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * The admin UI cookie is revoked through mangoo's blacklist: on logout, and for every other session
 * of the account on a password or 2FA change. Each test signs in with its own superadmin, so a
 * revocation never reaches the "admin" account the other test classes share.
 */
@ExtendWith({TestRunner.class})
class AdminCookieRevocationIntegrationTest {
    private static final String COOKIE = "paprika-authentication";
    private static final String PASSWORD = "cookie-revocation-password-1";
    private static final String NEW_PASSWORD = "cookie-revocation-password-2";

    @Test
    void aPasswordChangeEndsEveryOtherSessionAndKeepsTheCurrentOne() throws InterruptedException {
        String username = "cookie-password-" + DbUtils.id().substring(0, 8);
        String adminId = createSuperadmin(username);
        try {
            HttpCookie otherDevice = signIn(username, PASSWORD, null);
            HttpCookie thisDevice = signIn(username, PASSWORD, null);
            awaitNextSecond();

            TestResponse changed = postJson("/api/admin/profile/password", thisDevice,
                    "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}");
            assertThat(changed.getContent(), changed.getStatusCode(), equalTo(StatusCodes.OK));
            HttpCookie reissued = changed.getCookie(COOKIE);

            assertThat("a session from before the change must end", profile(otherDevice), equalTo(StatusCodes.UNAUTHORIZED));
            assertThat("a copy of the cookie that made the change must end too", profile(thisDevice),
                    equalTo(StatusCodes.UNAUTHORIZED));
            assertThat("the session that made the change stays signed in", reissued, notNullValue());
            assertThat(profile(reissued), equalTo(StatusCodes.OK));

            HttpCookie again = signIn(username, NEW_PASSWORD, otherDevice);
            assertThat("a browser still holding the revoked cookie must get a new one on sign-in",
                    again.getValue(), not(equalTo(otherDevice.getValue())));
            assertThat(profile(again), equalTo(StatusCodes.OK));
        } finally {
            AdminTestUtils.removeSuperadmin(adminId);
        }
    }

    @Test
    void enablingTwoFactorEndsEverySessionThatNeverPassedIt() throws Exception {
        String username = "cookie-2fa-" + DbUtils.id().substring(0, 8);
        String adminId = createSuperadmin(username);
        try {
            HttpCookie otherDevice = signIn(username, PASSWORD, null);
            HttpCookie thisDevice = signIn(username, PASSWORD, null);
            awaitNextSecond();

            TestResponse setup = postJson("/api/admin/profile/2fa/setup", thisDevice,
                    "{\"password\":\"" + PASSWORD + "\"}");
            assertThat(setup.getContent(), setup.getStatusCode(), equalTo(StatusCodes.OK));
            String secret = JsonUtils.getMapper().readTree(setup.getContent()).get("secret").asText();

            TestResponse confirmed = TestRequest.post("/api/admin/profile/2fa/confirm")
                    .withCookie(thisDevice)
                    .withCookie(setup.getCookie("paprika-session"))
                    .withStringBody("{\"code\":\"" + TotpUtils.getTotp(secret) + "\"}")
                    .withContentType("application/json")
                    .execute();
            assertThat(confirmed.getContent(), confirmed.getStatusCode(), equalTo(StatusCodes.OK));

            assertThat(profile(otherDevice), equalTo(StatusCodes.UNAUTHORIZED));
            assertThat(profile(confirmed.getCookie(COOKIE)), equalTo(StatusCodes.OK));
        } finally {
            AdminTestUtils.removeSuperadmin(adminId);
        }
    }

    @Test
    void aLogoutRevokesTheCookieInsteadOfOnlyDeletingItInTheBrowser() {
        String username = "cookie-logout-" + DbUtils.id().substring(0, 8);
        String adminId = createSuperadmin(username);
        try {
            HttpCookie cookie = signIn(username, PASSWORD, null);
            HttpCookie otherDevice = signIn(username, PASSWORD, null);
            assertThat(profile(cookie), equalTo(StatusCodes.OK));

            TestResponse logout = TestRequest.post("/logout").withCookie(cookie).execute();
            assertThat(logout.getStatusCode(), anyOf(equalTo(StatusCodes.FOUND), equalTo(StatusCodes.OK)));

            assertThat("a copy kept from before the logout must not work", profile(cookie),
                    equalTo(StatusCodes.UNAUTHORIZED));
            assertThat("a logout ends only its own session", profile(otherDevice), equalTo(StatusCodes.OK));
        } finally {
            AdminTestUtils.removeSuperadmin(adminId);
        }
    }

    private static String createSuperadmin(String username) {
        return String.valueOf(Application.getInstance(SystemUserService.class)
                .createSuperadmin(username, null, PASSWORD).get("id"));
    }

    private static HttpCookie signIn(String username, String password, HttpCookie heldCookie) {
        Multimap<String, String> form = ArrayListMultimap.create();
        form.put("username", username);
        form.put("password", password);

        TestResponse request = TestRequest.post("/authenticate").withForm(form);
        if (heldCookie != null) {
            request = request.withCookie(heldCookie);
        }

        HttpCookie cookie = request.execute().getCookie(COOKIE);
        assertThat("admin UI sign-in", cookie, notNullValue());
        return cookie;
    }

    private static int profile(HttpCookie cookie) {
        return TestRequest.get("/api/admin/profile").withCookie(cookie).execute().getStatusCode();
    }

    private static TestResponse postJson(String uri, HttpCookie cookie, String body) {
        return TestRequest.post(uri)
                .withCookie(cookie)
                .withStringBody(body)
                .withContentType("application/json")
                .execute();
    }

    // A cookie's iat has second precision and one issued within the second of a revocation stays
    // valid, so the revocation has to land in a later second than the sign-ins it should catch
    private static void awaitNextSecond() throws InterruptedException {
        Thread.sleep(1000 - System.currentTimeMillis() % 1000 + 50);
    }
}
