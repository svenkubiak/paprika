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

    /** The Location header is asserted, since a wrong setup makes mangoo fall back to its error page. */
    @Test
    public void testIndexPageRedirectsToLogin() {
        TestResponse response = TestRequest.get("/").withDisabledRedirects().execute();

        assertThat(response, not(nullValue()));
        assertThat(response.getStatusCode(), equalTo(StatusCodes.FOUND));
        assertThat(response.getHeader("Location"), equalTo("/login?origin=%2F"));
    }

    @Test
    public void testTheRedirectKeepsTheRequestedPath() {
        TestResponse response = TestRequest.get("/admin/tenants").withDisabledRedirects().execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.FOUND));
        assertThat(response.getHeader("Location"), equalTo("/login?origin=%2Fadmin%2Ftenants"));
    }

    /** Without a server-side route, a reload of an SPA route ends on the framework's 404 page. */
    @Test
    public void testEveryAdminUiRouteIsServedByTheShell() {
        for (String path : new String[]{
                "/admin/tenants", "/admin/settings", "/admin/global-hooks", "/admin/tenant-settings",
                "/admin/logs", "/admin/users", "/admin/user-settings", "/admin/backup",
                "/admin/superadmins", "/admin/profile", "/admin/collections/users/data"}) {
            TestResponse response = TestRequest.get(path).withDisabledRedirects().execute();

            assertThat("no server route for " + path,
                    response.getStatusCode(), equalTo(StatusCodes.FOUND));
            assertThat(response.getHeader("Location"), startsWith("/login?origin="));
        }
    }

    /** A redirect would reach the SPA as an HTML body with status 200, indistinguishable from data. */
    @Test
    public void testBootstrapReportsAMissingSessionAsJson() {
        TestResponse response = TestRequest.get("/admin/bootstrap").withDisabledRedirects().execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContentType(), containsString("application/json"));
        assertThat(response.getContent(), containsString("\"authenticated\":false"));
        assertThat(response.getContent(), containsString("\"tenants\":[]"));
        assertThat(response.getContent(), containsString("\"collections\":[]"));
        assertThat(response.getContent(), containsString("\"activeTenant\":null"));
    }

    @Test
    public void testTheLoginPageIsServedWithoutATenant() {
        TestResponse response = TestRequest.get("/login").execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), containsString("id=\"app\""));
    }

    /** The admin UI turns this 401 into its "signed out" notice. */
    @Test
    public void testTheAdminApiAnswersAnExpiredSessionWithUnauthorized() {
        TestResponse response = TestRequest.get("/api/meta/tenants").execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat(response.getContent(), containsString("Unauthorized"));
    }

    @Test
    void oneTimeSetupTokenCreatesSuperadminPassword() {
        // "admin" must be a completed superadmin, so the cleanup below can delete the second one
        AdminTestUtils.prepareAdminPassword();
        String username = "setup-" + CommonUtils.uuidV7();
        SystemUserService users = Application.getInstance(SystemUserService.class);
        String token = users.createSuperadminSetup(username, null);

        // Cleanup must run even on failure: a leftover superadmin would let lastSuperadminCannotBeRemoved
        // delete "admin" on the next run.
        SystemUserService.DeleteOutcome cleanup;
        try {
            TestResponse login = TestRequest.post("/api/admin/login")
                    .withStringBody(
                            "{\"username\":\"" + username + "\",\"password\":\"permanent-password-123\"}")
                    .withContentType("application/json")
                    .execute();
            assertThat(login.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
            assertThat(login.getCookie("paprika-authentication"), nullValue());

            // A complete body, or Bean Validation rejects it before the password length rule
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

            // A complete body, so the rejection can only come from the consumed token
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

        // A silently failed cleanup would poison later classes
        assertThat("the superadmin created here must not outlive this test",
                cleanup, equalTo(SystemUserService.DeleteOutcome.DELETED));
    }

    /** An unfiltered paprika-version.properties degrades silently to "unknown", so only this notices. */
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
    void superadminCanChangePasswordOnTheirProfile() {
        String currentPassword = AdminTestUtils.prepareAdminPassword();
        HttpCookie authentication = AdminTestUtils.loginAsAdmin();

        TestResponse response = TestRequest.post("/api/admin/profile/password")
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