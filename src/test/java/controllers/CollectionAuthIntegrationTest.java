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
import utils.TenantTestUtils;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@ExtendWith({TestRunner.class})
class CollectionAuthIntegrationTest {

    @Test
    void createWithAccessTokenAndAuthRule() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("api-user", null, "secret-password-123");

        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("api-user", "secret-password-123"))
                .withContentType("application/json")
                .execute();

        assertThat(login.getStatusCode(), equalTo(StatusCodes.OK));

        String accessToken = extractJsonString(login.getContent(), "accessToken");
        String collection = "posts_auth_test";
        TenantTestUtils.seedCollection(collection, new CollectionRules(null, null, "auth", null, null, "owner"));

        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withHeader("Authorization", "Bearer " + accessToken)
                .withStringBody("{\"title\":\"hello\"}")
                .withContentType("application/json")
                .execute();

        assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));
    }

    @Test
    void createWithAccessTokenButLockedRuleReturnsForbidden() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("locked-user", null, "secret-password-123");

        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("locked-user", "secret-password-123"))
                .withContentType("application/json")
                .execute();

        String accessToken = extractJsonString(login.getContent(), "accessToken");
        String collection = "posts_locked_test";
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());

        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withHeader("Authorization", "Bearer " + accessToken)
                .withStringBody("{\"title\":\"hello\"}")
                .withContentType("application/json")
                .execute();

        assertThat(create.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
    }

    @Test
    void createWithRefreshTokenAndAuthRuleReturnsBadRequest() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("refresh-user", null, "secret-password-123");

        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("refresh-user", "secret-password-123"))
                .withContentType("application/json")
                .execute();

        String refreshToken = extractJsonString(login.getContent(), "refreshToken");
        String collection = "posts_refresh_test";
        TenantTestUtils.seedCollection(collection, new CollectionRules(null, null, "auth", null, null, "owner"));

        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withHeader("Authorization", "Bearer " + refreshToken)
                .withStringBody("{\"title\":\"hello\"}")
                .withContentType("application/json")
                .execute();

        assertThat(create.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
    }

    @Test
    void createWithLockedRuleAndNoSchemaFieldsReturnsUnauthorized() {
        String collection = "posts_no_schema_locked";
        TenantTestUtils.seedCollection(collection, CollectionRules.locked(), List.of());

        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withStringBody("{\"anything\":\"value\"}")
                .withContentType("application/json")
                .execute();

        assertThat(create.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
    }

    @Test
    void listWithAuthRuleAndNoCredentialsReturnsUnauthorized() {
        String collection = "posts_auth_list_test";
        TenantTestUtils.seedCollection(collection, new CollectionRules("auth", null, null, null, null, "owner"));
        TenantTestUtils.seedRecord(collection, "Visible record");

        TestResponse response = TestRequest.get("/api/collections/" + collection + "?offset=0&limit=25")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
    }

    @Test
    void invalidBearerTokenReturnsUnauthorized() {
        String collection = "posts_invalid_bearer_test";
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());

        TestResponse response = TestRequest.get("/api/collections/" + collection + "?offset=0&limit=25")
                .withHeader("Authorization", "Bearer not-a-valid-token")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat(response.getContent(), containsString("\"error\":\"Unauthorized\""));
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
