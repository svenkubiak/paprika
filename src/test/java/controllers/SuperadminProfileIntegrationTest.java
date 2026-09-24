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

/**
 * The profile a superadmin keeps of their own account. Everything here acts on the account behind
 * the session, so the tests sign in and then only ever talk about "me" - there is deliberately no
 * route that takes a user id.
 * <p>
 * The test classes share one application and one database, so each test puts the account back the
 * way it found it.
 */
@ExtendWith({TestRunner.class})
class SuperadminProfileIntegrationTest {
    /** A real 1x1 PNG - small enough to inline, and Tika has to recognise it as an image. */
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

    /**
     * Storing an address must never count as owning it: until the confirmation link comes back, the
     * account is in the same state as one without an address, and nothing is sent there.
     */
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

    /**
     * The declared type only decides how the bytes would be handed back, so it cannot be the thing
     * that is trusted: a document announced as a PNG would otherwise be served with a content type
     * that makes a browser render it.
     */
    @Test
    void anImageThatIsNotAnImageIsRejected() {
        HttpCookie auth = AdminTestUtils.loginAsAdmin();
        String payload = Base64.getEncoder()
                .encodeToString("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));

        TestResponse response = postJson(
                "/api/admin/profile/avatar", auth, "{\"image\":\"data:image/png;base64," + payload + "\"}");

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("not a PNG, JPEG or WebP image"));
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
