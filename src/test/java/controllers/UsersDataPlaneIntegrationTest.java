package controllers;

import auth.TenantContext;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionDefinition;
import models.CollectionRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import services.UserService;
import utils.TenantTestUtils;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@ExtendWith({TestRunner.class})
class UsersDataPlaneIntegrationTest {

    @Test
    void dataPlaneRespectsCredentialProtectionAndRoleGuard() {
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        CollectionDefinition original = collections.findDefinition(ctx, "users");

        openUsersRules(collections, ctx, original, new CollectionRules("*", "*", "auth", "auth", "auth", "owner"));

        try {
            UserService userService = Application.getInstance(UserService.class);
            Map<String, Object> bootstrap = userService.createUser("dp-bootstrap", null, "secret-password-123");
            String bootstrapId = String.valueOf(bootstrap.get("id"));

            String accessToken = login("dp-bootstrap", "secret-password-123");

            // Create a user through the data-plane, attempting to escalate the role.
            TestResponse create = TestRequest.post("/api/collections/users")
                    .withHeader("Authorization", "Bearer " + accessToken)
                    .withStringBody("""
                            {"username":"dp-created","email":"dp@example.com",
                             "password":"another-secret-123","role":"superadmin"}
                            """)
                    .withContentType("application/json")
                    .execute();
            assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));

            // Listing must never leak credential fields, and the escalated role must be forced to "user".
            TestResponse list = TestRequest.get("/api/collections/users")
                    .withHeader("Authorization", "Bearer " + accessToken)
                    .execute();
            assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(list.getContent(), containsString("dp-created"));
            assertThat(list.getContent(), not(containsString("passwordHash")));
            assertThat(list.getContent(), not(containsString("passwordSalt")));
            assertThat(list.getContent(), not(containsString("superadmin")));

            // The password set through the data-plane must be a valid, hashed credential.
            String createdToken = login("dp-created", "another-secret-123");
            assertThat(createdToken.isBlank(), equalTo(false));

            // A user cannot escalate their own role through an update either.
            TestResponse update = TestRequest.patch("/api/collections/users/" + bootstrapId)
                    .withHeader("Authorization", "Bearer " + accessToken)
                    .withStringBody("{\"role\":\"superadmin\"}")
                    .withContentType("application/json")
                    .execute();
            assertThat(update.getStatusCode(), equalTo(StatusCodes.OK));

            TestResponse afterUpdate = TestRequest.get("/api/collections/users/" + bootstrapId)
                    .withHeader("Authorization", "Bearer " + accessToken)
                    .execute();
            assertThat(afterUpdate.getContent(), containsString("\"role\":\"user\""));
            assertThat(afterUpdate.getContent(), not(containsString("passwordHash")));

            // Password is required when creating a user through the data-plane.
            TestResponse missingPassword = TestRequest.post("/api/collections/users")
                    .withHeader("Authorization", "Bearer " + accessToken)
                    .withStringBody("{\"username\":\"dp-nopass\"}")
                    .withContentType("application/json")
                    .execute();
            assertThat(missingPassword.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        } finally {
            collections.replaceDefinition(ctx, original);
        }
    }

    private static void openUsersRules(
            TenantCollectionService collections,
            TenantContext ctx,
            CollectionDefinition original,
            CollectionRules rules) {

        collections.replaceDefinition(ctx, new CollectionDefinition(
                original.id(),
                original.name(),
                original.fields(),
                original.indexes(),
                rules,
                original.system()));
    }

    private static String login(String username, String password) {
        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("default", username, password))
                .withContentType("application/json")
                .execute();
        assertThat(login.getStatusCode(), equalTo(StatusCodes.OK));
        return extractJsonString(login.getContent(), "accessToken");
    }

    private static String extractJsonString(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("Field not found: " + field);
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
