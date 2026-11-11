package controllers;

import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.SystemUserService;
import utils.AdminTestUtils;

import java.net.HttpCookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/** The test classes share one application and database, so each test restores the account it changed. */
@ExtendWith({TestRunner.class})
class SuperadminProfileIntegrationTest {
    /** A real 1x1 PNG, because Tika has to recognise it as an image. */
    private static final String PNG_1X1 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    @AfterEach
    void resetProfile() {
        SystemUserService users = Application.getInstance(SystemUserService.class);
        String adminId = String.valueOf(users.findPublicUserByUsername("admin").orElseThrow().get("id"));
        users.clearEmail(adminId);
        users.clearAvatar(adminId);
    }

    @Test
    void theProfileDescribesTheSignedInAccount() {
        HttpCookie auth = AdminTestUtils.loginAsAdmin();

        TestResponse response = TestRequest.get("/api/admin/profile").withCookie(auth).execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), containsString("\"username\":\"admin\""));
        assertThat("an address that was never confirmed must not read as confirmed",
                response.getContent(), containsString("\"emailVerified\":false"));
    }

    @Test
    void anAddressHasToLookLikeOne() {
        HttpCookie auth = AdminTestUtils.loginAsAdmin();

        TestResponse response = postJson("/api/admin/profile/email", auth, "{\"email\":\"not-an-address\"}");

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("valid email address"));
    }

    /** Until the confirmation link comes back, nothing may be sent to a stored address. */
    @Test
    void aStoredAddressStaysUnconfirmedAndKeepsTheAlertOff() {
        HttpCookie auth = AdminTestUtils.loginAsAdmin();

        TestResponse stored = postJson("/api/admin/profile/email", auth, "{\"email\":\"Admin@Example.Test\"}");
        assertThat(stored.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat("addresses are stored lowercased, like everywhere else",
                stored.getContent(), containsString("\"email\":\"admin@example.test\""));
        assertThat(stored.getContent(), containsString("\"emailVerified\":false"));

        TestResponse alert = postJson("/api/admin/profile/login-alert", auth, "{\"enabled\":true}");
        assertThat("an unconfirmed address cannot receive the alert, so the switch must refuse",
                alert.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(alert.getContent(), containsString("Confirm your email address"));

        TestResponse removed = TestRequest.delete("/api/admin/profile/email").withCookie(auth).execute();
        assertThat(removed.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(removed.getContent(), containsString("\"email\":null"));
    }

    /** A new link replaces the previous one, so a mail that arrived late cannot confirm the address. */
    @Test
    void resendingTheConfirmationInvalidatesThePreviousLink() {
        HttpCookie auth = AdminTestUtils.loginAsAdmin();
        SystemUserService users = Application.getInstance(SystemUserService.class);
        String adminId = String.valueOf(users.findPublicUserByUsername("admin").orElseThrow().get("id"));

        TestResponse none = postJson("/api/admin/profile/email/resend", auth, "{}");
        assertThat(none.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(none.getContent(), containsString("No email address"));

        assertThat(postJson("/api/admin/profile/email", auth, "{\"email\":\"admin@example.test\"}").getStatusCode(),
                equalTo(StatusCodes.OK));
        String first = users.renewEmailVerificationToken(adminId).orElseThrow();

        TestResponse resent = postJson("/api/admin/profile/email/resend", auth, "{}");
        assertThat(resent.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(resent.getContent(), containsString("\"emailVerified\":false"));

        TestResponse stale = TestRequest.post("/api/admin/verify-email")
                .withStringBody("{\"token\":\"" + first + "\"}")
                .withContentType("application/json")
                .execute();
        assertThat(stale.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(stale.getContent(), containsString("invalid or has expired"));

        String second = users.renewEmailVerificationToken(adminId).orElseThrow();
        TestResponse confirmed = TestRequest.post("/api/admin/verify-email")
                .withStringBody("{\"token\":\"" + second + "\"}")
                .withContentType("application/json")
                .execute();
        assertThat(confirmed.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(confirmed.getContent(), containsString("\"success\":true"));

        TestResponse again = postJson("/api/admin/profile/email/resend", auth, "{}");
        assertThat(again.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(again.getContent(), containsString("already confirmed"));
    }

    @Test
    void aPasswordChangeNeedsTheCurrentPasswordAndADifferentNewOne() {
        String current = AdminTestUtils.prepareAdminPassword();
        HttpCookie auth = AdminTestUtils.loginAsAdmin();

        TestResponse wrong = postJson("/api/admin/profile/password", auth,
                "{\"currentPassword\":\"not-the-password\",\"newPassword\":\"another-password-123\"}");
        assertThat(wrong.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat(wrong.getContent(), containsString("Invalid current password"));

        TestResponse same = postJson("/api/admin/profile/password", auth,
                "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + current + "\"}");
        assertThat(same.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(same.getContent(), containsString("must be different"));
    }

    @Test
    void anUnknownConfirmationTokenIsRejectedWithoutASession() {
        TestResponse response = TestRequest.post("/api/admin/verify-email")
                .withStringBody("{\"token\":\"not-a-real-token\"}")
                .withContentType("application/json")
                .execute();

        assertThat("confirming is reachable without a session - the token is the credential",
                response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("invalid or has expired"));
    }

    @Test
    void theProfilePictureIsStoredAndServedBack() {
        HttpCookie auth = AdminTestUtils.loginAsAdmin();

        TestResponse stored = postJson(
                "/api/admin/profile/avatar", auth, "{\"image\":\"data:image/png;base64," + PNG_1X1 + "\"}");
        assertThat(stored.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(stored.getContent(), containsString("/api/admin/profile/avatar?v="));

        TestResponse served = TestRequest.get("/api/admin/profile/avatar").withCookie(auth).execute();
        assertThat(served.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(served.getHeader("Content-Type"), containsString("image/png"));
        assertThat("a shared cache must never hand this to another session",
                served.getHeader("Cache-Control"), containsString("private"));
        assertThat(served.getHeader("ETag"), not(emptyOrNullString()));

        TestResponse removed = TestRequest.delete("/api/admin/profile/avatar").withCookie(auth).execute();
        assertThat(removed.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(removed.getContent(), containsString("\"avatarUrl\":null"));

        assertThat(TestRequest.get("/api/admin/profile/avatar").withCookie(auth).execute().getStatusCode(),
                equalTo(StatusCodes.NOT_FOUND));
    }

    /** The declared type is untrusted: a document announced as PNG would be served as one and rendered. */
    @Test
    void anImageThatIsNotAnImageIsRejected() {
        HttpCookie auth = AdminTestUtils.loginAsAdmin();
        String payload = Base64.getEncoder()
                .encodeToString("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));

        TestResponse response = postJson(
                "/api/admin/profile/avatar", auth, "{\"image\":\"data:image/png;base64," + payload + "\"}");

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("not a PNG, JPEG or WebP image"));

        TestResponse notAUrl = postJson("/api/admin/profile/avatar", auth, "{\"image\":\"hello\"}");
        assertThat(notAUrl.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(notAUrl.getContent(), containsString("base64 image data URL"));

        TestResponse gif = postJson("/api/admin/profile/avatar", auth,
                "{\"image\":\"data:image/gif;base64," + PNG_1X1 + "\"}");
        assertThat(gif.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(gif.getContent(), containsString("Only PNG, JPEG and WebP"));
    }

    @Test
    void theProfileIsUnreachableWithoutASession() {
        for (String uri : new String[]{"/api/admin/profile", "/api/admin/profile/avatar"}) {
            assertThat(uri, TestRequest.get(uri).execute().getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        }
    }

    private static TestResponse postJson(String uri, HttpCookie auth, String body) {
        return TestRequest.post(uri)
                .withCookie(auth)
                .withStringBody(body)
                .withContentType("application/json")
                .execute();
    }
}
