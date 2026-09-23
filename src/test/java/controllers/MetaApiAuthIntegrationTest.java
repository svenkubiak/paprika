package controllers;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import constants.CollectionName;
import enums.Role;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.SystemUserService;
import services.TenantDatabaseResolver;
import services.UserService;
import utils.AdminTestUtils;
import utils.TenantTestUtils;

import java.net.HttpCookie;
import java.util.List;
import java.util.Map;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.set;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@ExtendWith({TestRunner.class})
class MetaApiAuthIntegrationTest {

    @Test
    void unauthenticatedMetaReadReturnsUnauthorized() {
        String collection = "meta_unauth_test";
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());

        TestResponse response = TestRequest.get("/api/meta/collections/" + collection)
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat(response.getContent(), containsString("\"error\":\"Unauthorized\""));
    }

    @Test
    void bearerTokenOnMetaApiReturnsForbidden() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("meta-bearer-user", null, "secret-password-123");

        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("meta-bearer-user", "secret-password-123"))
                .withContentType("application/json")
                .execute();

        String accessToken = extractJsonString(login.getContent(), "accessToken");
        String collection = "meta_bearer_test";
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());

        TestResponse response = TestRequest.get("/api/meta/collections/" + collection)
                .withHeader("Authorization", "Bearer " + accessToken)
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
        assertThat(response.getContent(), containsString("\"error\":\"Forbidden\""));
    }

    /**
     * The writing admin routes are asserted separately from the reading ones: a filter is declared
     * per controller, but a route added later could arrive on a controller of its own, and a
     * schema or backup import is the most damaging thing a bearer could reach.
     */
    @Test
    void bearerTokenOnImportRoutesReturnsForbidden() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("meta-import-bearer-user", null, "secret-password-123");

        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("meta-import-bearer-user", "secret-password-123"))
                .withContentType("application/json")
                .execute();

        String accessToken = extractJsonString(login.getContent(), "accessToken");

        for (String uri : List.of("/api/meta/schema/import", "/api/admin/backup/import")) {
            TestResponse response = TestRequest.post(uri)
                    .withHeader("Authorization", "Bearer " + accessToken)
                    .withStringBody("{\"collections\":[]}")
                    .withContentType("application/json")
                    .execute();

            assertThat(uri + " must reject a bearer token",
                    response.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
            assertThat(response.getContent(), containsString("\"error\":\"Forbidden\""));
        }
    }

    /**
     * RFC 7235 makes the authentication scheme case-insensitive. A lower case {@code bearer} must
     * therefore be rejected the same way, and must not be mistaken for a request that carries no
     * bearer at all - which would let it fall through to the cookie branch of the filter.
     */
    @Test
    void lowercaseBearerSchemeOnMetaApiReturnsForbidden() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("meta-lowercase-bearer-user", null, "secret-password-123");

        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("meta-lowercase-bearer-user", "secret-password-123"))
                .withContentType("application/json")
                .execute();

        String accessToken = extractJsonString(login.getContent(), "accessToken");
        String collection = "meta_lowercase_bearer_test";
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());

        TestResponse response = TestRequest.get("/api/meta/collections/" + collection)
                .withHeader("Authorization", "bearer " + accessToken)
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
        assertThat(response.getContent(), containsString("\"error\":\"Forbidden\""));
    }

    /**
     * The session cookie only carries a subject, so the authority behind it has to be read off the
     * stored account on every request. An account that is no longer a superadmin must lose its
     * admin session immediately, instead of keeping it until the cookie expires.
     */
    @Test
    void sessionOfAnAccountThatIsNoLongerASuperadminIsRejected() {
        SystemUserService systemUserService = Application.getInstance(SystemUserService.class);
        Map<String, Object> created = systemUserService.createSuperadmin(
                "meta-demoted-admin", null, "demoted-password-123");
        String userId = String.valueOf(created.get("id"));

        HttpCookie cookie = login("meta-demoted-admin", "demoted-password-123");
        AdminTestUtils.AdminCookies cookies = new AdminTestUtils.AdminCookies(cookie, null);

        TestResponse asSuperadmin = AdminTestUtils.getWithAdminCookies("/api/admin/settings", cookies);
        assertThat(asSuperadmin.getStatusCode(), equalTo(StatusCodes.OK));

        try {
            Application.getInstance(TenantDatabaseResolver.class)
                    .systemCollection(CollectionName.USERS)
                    .updateOne(eq("id", userId), set("role", Role.USER));

            TestResponse afterDemotion = AdminTestUtils.getWithAdminCookies("/api/admin/settings", cookies);
            assertThat(afterDemotion.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
            assertThat(afterDemotion.getContent(), containsString("\"error\":\"Unauthorized\""));
        } finally {
            Application.getInstance(TenantDatabaseResolver.class)
                    .systemCollection(CollectionName.USERS)
                    .deleteOne(eq("id", userId));
        }
    }

    @Test
    void adminCookieCanReadMetaCollection() {
        String collection = "meta_admin_read_test";
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());

        AdminTestUtils.AdminCookies adminCookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
        TestResponse response = AdminTestUtils.getWithAdminCookies("/api/meta/collections/" + collection, adminCookies);

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), containsString("\"name\":\"" + collection + "\""));
    }

    private HttpCookie login(String username, String password) {
        Multimap<String, String> form = ArrayListMultimap.create();
        form.put("username", username);
        form.put("password", password);

        TestResponse login = TestRequest.post("/authenticate")
                .withForm(form)
                .execute();

        assertThat(login.getStatusCode(), anyOf(equalTo(StatusCodes.FOUND), equalTo(StatusCodes.OK)));

        HttpCookie cookie = login.getCookie("paprika-authentication");
        if (cookie == null) {
            throw new IllegalStateException("Missing paprika-authentication cookie after login");
        }
        return cookie;
    }

    private String extractJsonString(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("Missing " + field + " in: " + json);
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
