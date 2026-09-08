package controllers;

import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.UserService;
import utils.AdminTestUtils;
import utils.TenantTestUtils;

import static org.hamcrest.MatcherAssert.assertThat;
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

    @Test
    void adminCookieCanReadMetaCollection() {
        String collection = "meta_admin_read_test";
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());

        AdminTestUtils.AdminCookies adminCookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
        TestResponse response = AdminTestUtils.getWithAdminCookies("/api/meta/collections/" + collection, adminCookies);

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), containsString("\"name\":\"" + collection + "\""));
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
