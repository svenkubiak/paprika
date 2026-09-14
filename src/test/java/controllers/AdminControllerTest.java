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

import java.net.HttpCookie;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@ExtendWith({TestRunner.class})
public class AdminControllerTest {

    @Test
    public void testIndexPageRequiresAuthentication() {
        TestResponse response = TestRequest.get("/").execute();

        assertThat(response, not(nullValue()));
        assertThat(response.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
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

            TestResponse shortPassword = TestRequest.post("/api/admin/setup")
                    .withStringBody(
                            "{\"token\":\"" + token + "\",\"password\":\"too-short\"}")
                    .withContentType("application/json")
                    .execute();
            assertThat(shortPassword.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));

            TestResponse completed = TestRequest.post("/api/admin/setup")
                    .withStringBody(
                            "{\"token\":\"" + token + "\",\"username\":\"" + username + "\",\"password\":\"permanent-password-123\"}")
                    .withContentType("application/json")
                    .execute();

            assertThat(completed.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(completed.getCookie("paprika-authentication"), not(nullValue()));
            assertThat(users.authenticateSuperadmin(username, "permanent-password-123").isPresent(), equalTo(true));

            TestResponse replay = TestRequest.post("/api/admin/setup")
                    .withStringBody(
                            "{\"token\":\"" + token + "\",\"password\":\"another-password-123\"}")
                    .withContentType("application/json")
                    .execute();
            assertThat(replay.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        } finally {
            cleanup = users.findPublicUserByUsername(username)
                    .map(user -> users.deleteSuperadmin(String.valueOf(user.get("id"))))
                    .orElse(SystemUserService.DeleteOutcome.NOT_FOUND);
        }

        // Only reached when the body passed - a silently failed cleanup would poison later classes.
        assertThat("the superadmin created here must not outlive this test",
                cleanup, equalTo(SystemUserService.DeleteOutcome.DELETED));
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