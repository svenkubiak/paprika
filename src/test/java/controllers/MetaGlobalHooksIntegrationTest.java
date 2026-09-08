package controllers;

import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.AdminTestUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@ExtendWith({TestRunner.class})
class MetaGlobalHooksIntegrationTest {

    @Test
    void adminCanCreateListAndDeleteGlobalHook() {
        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        TestResponse create = AdminTestUtils.postWithAdminCookies(
                "/api/meta/global-hooks",
                cookies,
                """
                {
                  "name": "Global gate",
                  "url": "https://example.com/before-request",
                  "secret": "test-signing-secret",
                  "enabled": true,
                  "priority": 10,
                  "applyToAllCollections": true
                }
                """,
                "application/json"
        );

        assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));
        assertThat(create.getContent(), containsString("\"event\":\"beforeRequest\""));
        assertThat(create.getContent(), containsString("\"collection\":\"*\""));

        TestResponse list = AdminTestUtils.getWithAdminCookies("/api/meta/global-hooks", cookies);

        assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(list.getContent(), containsString("Global gate"));

        String id = extractJsonString(create.getContent(), "id");

        TestResponse deleted = cookies.apply(
                TestRequest.delete("/api/meta/global-hooks/" + id)
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
