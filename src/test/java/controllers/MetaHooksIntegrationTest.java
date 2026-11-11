package controllers;

import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.AdminTestUtils;
import utils.DbUtils;
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

    @Test
    void adminCanRenameAHookAndABadPatchLeavesItUnchanged() {
        String collection = "hooks_update_" + System.nanoTime();
        TenantTestUtils.seedCollection(collection, new CollectionRules("*", "*", "*", "*", "*", "owner"));
        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
        String base = "/api/meta/collections/" + collection + "/hooks";

        TestResponse missing = AdminTestUtils.patchWithAdminCookies(
                base + "/" + DbUtils.id(), cookies, "{\"name\":\"Nope\"}", "application/json");
        assertThat(missing.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));

        TestResponse create = AdminTestUtils.postWithAdminCookies(
                base,
                cookies,
                """
                {
                  "name": "Notify",
                  "event": "afterCreate",
                  "url": "https://example.com/hook",
                  "secret": "test-signing-secret"
                }
                """,
                "application/json");
        assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));
        String id = extractJsonString(create.getContent(), "id");

        TestResponse renamed = AdminTestUtils.patchWithAdminCookies(
                base + "/" + id, cookies, "{\"name\":\"Renamed\"}", "application/json");
        assertThat(renamed.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(renamed.getContent(), containsString("\"name\":\"Renamed\""));
        assertThat(renamed.getContent(), containsString("https://example.com/hook"));

        TestResponse blocked = AdminTestUtils.patchWithAdminCookies(
                base + "/" + id,
                cookies,
                "{\"forwardHeaders\":[\"Authorization\"]}",
                "application/json");
        assertThat(blocked.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(blocked.getContent(), containsString("cannot be forwarded"));

        TestResponse traced = AdminTestUtils.patchWithAdminCookies(
                base + "/" + id, cookies, "{\"method\":\"TRACE\"}", "application/json");
        assertThat(traced.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(traced.getContent(), containsString("Hook method must be one of"));

        TestResponse list = AdminTestUtils.getWithAdminCookies(base, cookies);
        assertThat(list.getContent(), containsString("Renamed"));
        assertThat(list.getContent(), containsString("https://example.com/hook"));
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
