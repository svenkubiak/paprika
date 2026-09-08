package controllers;

import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionRules;
import models.FieldDefinition;
import models.FieldOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import services.UserService;
import utils.AdminTestUtils;
import utils.DbUtils;
import utils.TenantTestUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@ExtendWith({TestRunner.class})
class CollectionAdminReadIntegrationTest {

    @Test
    void adminCanCreateRecordAndListItWithLockedRules() {
        String collection = "posts_admin_create_list_test";
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());

        AdminTestUtils.AdminCookies adminCookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
        TestResponse create = AdminTestUtils.postWithAdminCookies(
                "/api/collections/" + collection,
                adminCookies,
                "{\"title\":\"Created from admin\"}",
                "application/json");

        assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));

        TestResponse list = AdminTestUtils.getWithAdminCookies(
                "/api/collections/" + collection + "?offset=0&limit=25",
                adminCookies);

        assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(list.getContent(), containsString("\"total\":1"));
        assertThat(list.getContent(), containsString("Created from admin"));
    }

    @Test
    void adminCookieReturnsEmptyListForCollectionWithoutRecords() {
        String collection = "posts_admin_empty_test";
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());

        AdminTestUtils.AdminCookies adminCookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
        TestResponse response = AdminTestUtils.getWithAdminCookies(
                "/api/collections/" + collection + "?offset=0&limit=10",
                adminCookies);

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), containsString("\"total\":0"));
        assertThat(response.getContent(), containsString("\"items\":[]"));
    }

    @Test
    void adminCookieBypassesLockedListRuleWithPagination() {
        String collection = "posts_admin_read_test";
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());
        seedRecords(collection, 5);

        AdminTestUtils.AdminCookies adminCookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
        TestResponse response = AdminTestUtils.getWithAdminCookies(
                "/api/collections/" + collection + "?offset=1&limit=2",
                adminCookies);

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), containsString("\"total\":5"));
        assertThat(response.getContent(), containsString("\"items\""));
    }

    @Test
    void publicReadReturnsUnauthorizedWhenListRuleLocked() {
        String collection = "posts_public_locked_test";
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());
        seedRecords(collection, 2);

        TestResponse response = TestRequest.get("/api/collections/" + collection + "?offset=0&limit=25")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat(response.getContent(), containsString("\"error\":\"Unauthorized\""));
    }

    @Test
    void publicReadReturnsNotFoundForUnknownCollection() {
        TestResponse response = TestRequest.get("/api/collections/unknown_collection_xyz?offset=0&limit=25")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
        assertThat(response.getContent(), containsString("\"error\":\"Collection not found\""));
    }

    @Test
    void bearerTokenDoesNotBypassLockedListRule() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("locked-bearer-user", null, "secret-password-123");

        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("locked-bearer-user", "secret-password-123"))
                .withContentType("application/json")
                .execute();

        String accessToken = extractJsonString(login.getContent(), "accessToken");
        String collection = "posts_bearer_locked_test";
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());
        seedRecords(collection, 2);

        TestResponse response = TestRequest.get("/api/collections/" + collection + "?offset=0&limit=25")
                .withHeader("Authorization", "Bearer " + accessToken)
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
        assertThat(response.getContent(), containsString("\"error\":\"Forbidden\""));
    }

    @Test
    void adminCanCreateRecordWithExplicitOwnerDespiteOwnerRules() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("admin-owner-target", null, "secret-password-123");
        String ownerId = findUserId("admin-owner-target");

        String collection = "posts_admin_owner_create_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("owner", "owner", "*", "owner", "owner", "owner"),
                java.util.List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition(
                                "owner",
                                FieldType.RELATION,
                                false,
                                true,
                                FieldOptions.forRelation("users"))));

        AdminTestUtils.AdminCookies adminCookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
        TestResponse create = AdminTestUtils.postWithAdminCookies(
                "/api/collections/" + collection,
                adminCookies,
                "{\"title\":\"Admin assigned owner\",\"owner\":\"" + ownerId + "\"}",
                "application/json");

        assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));
    }

    @Test
    void adminCookieSentAsBearerDoesNotBypassLockedListRule() {
        String collection = "posts_admin_bearer_locked_test";
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());
        seedRecords(collection, 2);

        AdminTestUtils.AdminCookies adminCookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
        TestResponse response = TestRequest.get("/api/collections/" + collection + "?offset=0&limit=25")
                .withHeader("Authorization", "Bearer " + adminCookies.authentication().getValue())
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat(response.getContent(), containsString("\"error\":\"Unauthorized\""));
    }

    private void seedRecords(String collection, int count) {
        for (int i = 0; i < count; i++) {
            TenantTestUtils.seedRecord(collection, "Record " + i);
        }
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

    private String findUserId(String username) {
        var ctx = TenantTestUtils.defaultTenantContext();
        var collections = Application.getInstance(TenantCollectionService.class);
        var user = collections.dataCollection(ctx, "users")
                .find(com.mongodb.client.model.Filters.eq("username", username))
                .first();
        if (user == null) {
            throw new IllegalStateException("Missing user: " + username);
        }
        return user.getString("id");
    }
}
