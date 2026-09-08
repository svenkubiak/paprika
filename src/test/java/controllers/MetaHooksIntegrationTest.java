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
class MetaHooksIntegrationTest {

    @Test
    void adminCanCreateListAndDeleteHook() {
        String collection = "hooks_meta_test";
        TenantTestUtils.seedCollection(collection, new CollectionRules("*", "*", "*", "*", "*", "owner"));

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        TestResponse create = AdminTestUtils.postWithAdminCookies(
                "/api/meta/collections/" + collection + "/hooks",
                cookies,
                """
                {
                  "name": "Test hook",
                  "event": "afterCreate",
                  "url": "https://example.com/hook",
                  "secret": "test-signing-secret",
                  "enabled": true,
                  "priority": 50
                }
                """,
                "application/json"
        );

        assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));
        assertThat(create.getContent(), containsString("\"name\":\"Test hook\""));

        TestResponse list = AdminTestUtils.getWithAdminCookies(
                "/api/meta/collections/" + collection + "/hooks",
                cookies
        );

        assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(list.getContent(), containsString("Test hook"));

        String id = extractJsonString(create.getContent(), "id");

        TestResponse deleted = cookies.apply(
                TestRequest.delete("/api/meta/collections/" + collection + "/hooks/" + id)
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
}
