package controllers;

import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.mangoo.utils.CommonUtils;
import io.undertow.util.StatusCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.SystemUserService;
import services.TwoFactorService;
import utils.AdminTestUtils;
import utils.AppVersion;

import java.net.HttpCookie;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@ExtendWith({TestRunner.class})
public class AdminControllerTest {

    /**
     * An unauthenticated browser hitting the admin UI has to land on the login page. Getting this
     * wrong is not a redirect to the wrong place, it is no redirect at all: mangoo falls back to
     * its default error page and the whole UI looks broken, so the Location header is asserted
     * rather than just the status code.
     *
     * The origin parameter comes from authentication.origin and is what lets the login page send
     * the user back to the page they were on, so it is part of the contract here.
     */
    @Test
    public void testIndexPageRedirectsToLogin() {
        TestResponse response = TestRequest.get("/").withDisabledRedirects().execute();

        assertThat(response, not(nullValue()));
        assertThat(response.getStatusCode(), equalTo(StatusCodes.FOUND));
        assertThat(response.getHeader("Location"), equalTo("/login?origin=%2F"));
    }

    /**
     * The path the session ran out on has to survive the redirect, otherwise signing back in
     * always dumps the admin on the dashboard instead of the page they were working on.
     */
    @Test
    public void testTheRedirectKeepsTheRequestedPath() {
        TestResponse response = TestRequest.get("/admin/tenants").withDisabledRedirects().execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.FOUND));
        assertThat(response.getHeader("Location"), equalTo("/login?origin=%2Fadmin%2Ftenants"));
    }

    /**
     * The admin and meta API do not redirect - they sit behind AdminAuthFilter and answer an
     * expired cookie with a 401, which is the signal the admin UI turns into the "you have been
     * signed out" notice. A different status here would leave that notice unreachable.
     */
    @Test
    public void testTheAdminApiAnswersAnExpiredSessionWithUnauthorized() {
        TestResponse response = TestRequest.get("/api/meta/tenants").execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat(response.getContent(), containsString("Unauthorized"));
    }

    @Test
    void oneTimeSetupTokenCreatesSuperadminPassword() {
        // Ensures "admin" is a completed superadmin before we create a second one below,
        // so the cleanup at the end of this test can actually delete it.
        AdminTestUtils.prepareAdminPassword();
        String username = "setup-" + CommonUtils.uuidV7();
        SystemUserService users = Application.getInstance(SystemUserService.class);
        String token = users.createSuperadminSetup(username, null);

        // The cleanup has to run even when an assertion below fails: a completed superadmin left in
        // the DB would let lastSuperadminCannotBeRemoved delete "admin" on the next test run,
        // cascading failures across the whole suite.
        SystemUserService.DeleteOutcome cleanup;
        try {
            TestResponse login = TestRequest.post("/api/admin/login")
                    .withStringBody(
                            "{\"username\":\"" + username + "\",\"password\":\"permanent-password-123\"}")
                    .withContentType("application/json")
                    .execute();
            assertThat(login.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
            assertThat(login.getCookie("paprika-authentication"), nullValue());

            // Sends a complete body on purpose: with only token and password this would be
            // rejected by Bean Validation for the missing username and would never reach the
            // password length rule it is here to cover.
            TestResponse shortPassword = TestRequest.post("/api/admin/setup")
                    .withStringBody(
                            "{\"token\":\"" + token + "\",\"username\":\"" + username + "\",\"password\":\"too-short\"}")
                    .withContentType("application/json")
                    .execute();
            assertThat(shortPassword.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
            assertThat(shortPassword.getContent(), containsString("Password must be at least"));

            TestResponse completed = TestRequest.post("/api/admin/setup")
                    .withStringBody(
                            "{\"token\":\"" + token + "\",\"username\":\"" + username + "\",\"password\":\"permanent-password-123\"}")
                    .withContentType("application/json")
                    .execute();

            assertThat(completed.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(completed.getCookie("paprika-authentication"), not(nullValue()));
            assertThat(users.authenticateSuperadmin(username, "permanent-password-123").isPresent(), equalTo(true));

            // Complete body again, so the rejection can only come from the consumed token and
            // not from a field the request happens to be missing.
            TestResponse replay = TestRequest.post("/api/admin/setup")
                    .withStringBody(
                            "{\"token\":\"" + token + "\",\"username\":\"" + username + "\",\"password\":\"another-password-123\"}")
                    .withContentType("application/json")
                    .execute();
            assertThat(replay.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
            assertThat(replay.getContent(), containsString("Setup token is invalid or expired"));
        } finally {
            cleanup = users.findPublicUserByUsername(username)
                    .map(user -> users.deleteSuperadmin(String.valueOf(user.get("id"))))
                    .orElse(SystemUserService.DeleteOutcome.NOT_FOUND);
        }

        // Only reached when the body passed - a silently failed cleanup would poison later classes.
        assertThat("the superadmin created here must not outlive this test",
                cleanup, equalTo(SystemUserService.DeleteOutcome.DELETED));
    }

    /**
     * The version the admin UI shows comes from paprika-version.properties, which only carries a
     * real value once Maven resource filtering has run. A missing or unfiltered file degrades to
     * "unknown" rather than failing, so nothing else would notice the build config breaking.
     */
    @Test
    void bootstrapReportsTheBuildVersion() {
        HttpCookie authentication = AdminTestUtils.loginAsAdmin();

        TestResponse response = TestRequest.get("/admin/bootstrap")
                .withCookie(authentication)
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), containsString("\"version\":\"" + AppVersion.get() + "\""));
        assertThat(AppVersion.get(), not(equalTo("unknown")));
        assertThat(AppVersion.get(), not(containsString("${")));
    }

    @Test
    void twoFactorEnforcementIsPerAccount() {
        AdminTestUtils.prepareAdminPassword();
        SystemUserService users = Application.getInstance(SystemUserService.class);
        String adminId = String.valueOf(users.findPublicUserByUsername("admin").orElseThrow().get("id"));

        TestResponse withoutSecret = TestRequest.post("/api/admin/login")
                .withStringBody("{\"username\":\"admin\",\"password\":\"admin-password-123\"}")
                .withContentType("application/json")
                .execute();
        assertThat(withoutSecret.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(withoutSecret.getContent(), containsString("success"));
        assertThat(withoutSecret.getCookie("paprika-authentication"), not(nullValue()));

        users.setTotpSecret(adminId, Application.getInstance(TwoFactorService.class).generateSecret());
        try {
            TestResponse withSecret = TestRequest.post("/api/admin/login")
                    .withStringBody("{\"username\":\"admin\",\"password\":\"admin-password-123\"}")
                    .withContentType("application/json")
                    .execute();
            assertThat(withSecret.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(withSecret.getContent(), containsString("requiresTwoFactor"));
            assertThat(withSecret.getCookie("paprika-authentication"), nullValue());
        } finally {
            users.clearTotpSecret(adminId);
        }
    }

    @Test
    void superadminCanChangePasswordInSettings() {
        String currentPassword = AdminTestUtils.prepareAdminPassword();
        HttpCookie authentication = AdminTestUtils.loginAsAdmin();

        TestResponse response = TestRequest.post("/api/admin/settings/password")
                .withCookie(authentication)
                .withStringBody(
                        "{\"currentPassword\":\"" + currentPassword
                                + "\",\"newPassword\":\"changed-password-123\"}")
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(
                Application.getInstance(SystemUserService.class)
                        .authenticateSuperadmin("admin", "changed-password-123")
                        .isPresent(),
                equalTo(true));
    }
}