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
import utils.AdminTestUtils;

import java.net.HttpCookie;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@ExtendWith({TestRunner.class})
public class SuperadminControllerTest {

    @Test
    void listRequiresAuthenticatedAdmin() {
        TestResponse response = TestRequest.get("/api/admin/superadmins").execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
    }

    @Test
    void inviteCreatesPendingSuperadminAndCanBeRevoked() {
        HttpCookie auth = AdminTestUtils.loginAsAdmin();
        String invitee = "invitee-" + CommonUtils.uuidV7();

        TestResponse list = TestRequest.get("/api/admin/superadmins").withCookie(auth).execute();
        assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(list.getContent(), containsString("admin"));

        TestResponse invite = TestRequest.post("/api/admin/superadmins")
                .withCookie(auth)
                .withStringBody("{\"username\":\"" + invitee + "\"}")
                .withContentType("application/json")
                .execute();
        assertThat(invite.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(invite.getContent(), containsString("/setup#token="));

        String inviteId = findSuperadminId(invitee);
        assertThat(inviteId, not(nullValue()));

        TestResponse revoke = TestRequest.delete("/api/admin/superadmins/" + inviteId)
                .withCookie(auth)
                .execute();
        assertThat(revoke.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(findSuperadminId(invitee), nullValue());
    }

    @Test
    void emailInviteAcceptsTokenAndEmail() {
        HttpCookie auth = AdminTestUtils.loginAsAdmin();

        TestResponse emailed = TestRequest.post("/api/admin/superadmins/invite/email")
                .withCookie(auth)
                .withStringBody("{\"token\":\"sample-token\",\"email\":\"invitee@example.com\","
                        + "\"username\":\"invitee\"}")
                .withContentType("application/json")
                .execute();

        assertThat(emailed.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(emailed.getContent(), containsString("\"success\":true"));
    }

    @Test
    void emailInviteRejectsMissingFields() {
        HttpCookie auth = AdminTestUtils.loginAsAdmin();

        TestResponse emailed = TestRequest.post("/api/admin/superadmins/invite/email")
                .withCookie(auth)
                .withStringBody("{\"token\":\"some-token\"}")
                .withContentType("application/json")
                .execute();

        assertThat(emailed.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
    }

    @Test
    void duplicateUsernameInviteIsRejected() {
        HttpCookie auth = AdminTestUtils.loginAsAdmin();

        TestResponse invite = TestRequest.post("/api/admin/superadmins")
                .withCookie(auth)
                .withStringBody("{\"username\":\"admin\"}")
                .withContentType("application/json")
                .execute();

        assertThat(invite.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
    }

    @Test
    void lastSuperadminCannotBeRemoved() {
        HttpCookie auth = AdminTestUtils.loginAsAdmin();
        SystemUserService users = Application.getInstance(SystemUserService.class);
        String adminId = String.valueOf(users.findPublicUserByUsername("admin").orElseThrow().get("id"));

        TestResponse response = TestRequest.delete("/api/admin/superadmins/" + adminId)
                .withCookie(auth)
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(users.findPublicUserByUsername("admin").isPresent(), equalTo(true));
    }

    @Test
    void deletingUnknownSuperadminReturnsNotFound() {
        HttpCookie auth = AdminTestUtils.loginAsAdmin();

        TestResponse response = TestRequest.delete("/api/admin/superadmins/does-not-exist")
                .withCookie(auth)
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
    }

    private String findSuperadminId(String username) {
        List<Map<String, Object>> superadmins = Application.getInstance(SystemUserService.class).listSuperadmins();
        return superadmins.stream()
                .filter(entry -> username.equals(entry.get("username")))
                .map(entry -> String.valueOf(entry.get("id")))
                .findFirst()
                .orElse(null);
    }
}
