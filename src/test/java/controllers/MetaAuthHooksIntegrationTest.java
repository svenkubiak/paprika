package controllers;

import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.AdminTestUtils;
import utils.TenantTestUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@ExtendWith({TestRunner.class})
class MetaAuthHooksIntegrationTest {

    @Test
    void adminCanCreateAuthHookOnUsersCollection() {
        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        TestResponse create = AdminTestUtils.postWithAdminCookies(
                "/api/meta/collections/users/hooks",
                cookies,
                """
                {
                  "name": "Registration webhook",
                  "event": "beforeRegister",
                  "url": "https://example.com/hook",
                  "secret": "test-signing-secret",
                  "enabled": true,
                  "priority": 50
                }
                """,
                "application/json"
        );

        assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));
        assertThat(create.getContent(), containsString("\"event\":\"beforeRegister\""));

        // Clean up: this hook lives on the shared default tenant and would otherwise fire (and
        // fail closed) during other auth tests.
        String id = extractJsonString(create.getContent(), "id");
        TestResponse deleted = cookies.apply(
                TestRequest.delete("/api/meta/collections/users/hooks/" + id)
        ).execute();
        assertThat(deleted.getStatusCode(), equalTo(StatusCodes.OK));
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

    @Test
    void authHookRejectedOnNonUsersCollection() {
        String collection = "auth_hooks_reject_test";
        TenantTestUtils.seedCollection(collection, new CollectionRules("*", "*", "*", "*", "*", "owner"));

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        TestResponse create = AdminTestUtils.postWithAdminCookies(
                "/api/meta/collections/" + collection + "/hooks",
                cookies,
                """
                {
                  "name": "Invalid auth hook",
                  "event": "beforeLogin",
                  "url": "https://example.com/hook",
                  "secret": "test-signing-secret",
                  "enabled": true
                }
                """,
                "application/json"
        );

        assertThat(create.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(create.getContent(), containsString("users collection"));
    }
}
