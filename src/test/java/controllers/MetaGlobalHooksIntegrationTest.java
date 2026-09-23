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
import static org.hamcrest.Matchers.not;

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

    /**
     * The file-route switch is off unless the operator sets it, and it must survive a save so the
     * admin UI can read it back - otherwise the opt-in silently resets on every edit.
     */
    @Test
    void includeFileRoutesDefaultsToOffAndRoundTripsThroughTheApi() {
        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        // Disabled on purpose: a global hook is tenant-wide, an enabled leftover would gate every
        // other request in this suite.
        TestResponse create = AdminTestUtils.postWithAdminCookies(
                "/api/meta/global-hooks",
                cookies,
                """
                {
                  "name": "File route gate",
                  "url": "https://example.com/before-request",
                  "secret": "test-signing-secret",
                  "enabled": false,
                  "applyToAllCollections": true
                }
                """,
                "application/json"
        );

        assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));
        assertThat("the switch is off unless it is set",
                create.getContent(), not(containsString("includeFileRoutes")));

        String id = extractJsonString(create.getContent(), "id");

        try {
            TestResponse enabled = cookies.apply(
                    TestRequest.patch("/api/meta/global-hooks/" + id)
                            .withStringBody("{\"includeFileRoutes\": true}")
                            .withContentType("application/json")
            ).execute();

            assertThat(enabled.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(enabled.getContent(), containsString("\"includeFileRoutes\":true"));

            TestResponse list = AdminTestUtils.getWithAdminCookies("/api/meta/global-hooks", cookies);
            assertThat(list.getContent(), containsString("\"includeFileRoutes\":true"));

            TestResponse disabled = cookies.apply(
                    TestRequest.patch("/api/meta/global-hooks/" + id)
                            .withStringBody("{\"includeFileRoutes\": false}")
                            .withContentType("application/json")
            ).execute();

            assertThat(disabled.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(disabled.getContent(), containsString("\"includeFileRoutes\":false"));
        } finally {
            cookies.apply(TestRequest.delete("/api/meta/global-hooks/" + id)).execute();
        }
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
