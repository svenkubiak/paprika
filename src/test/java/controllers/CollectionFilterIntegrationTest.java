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
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import services.UserService;
import utils.AdminTestUtils;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@ExtendWith({TestRunner.class})
class CollectionFilterIntegrationTest {

    // 1. The filter is only ever anded onto the rule filter. User A filtering for a value that only
    //    B's records carry must yield an empty list - never B's records, never a 403 detour.
    @Test
    void filterNeverEscapesOwnerScope() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("filter-owner-a", null, "secret-password-123");
        userService.createUser("filter-owner-b", null, "secret-password-456");

        String tokenA = loginToken("filter-owner-a", "secret-password-123");
        String tokenB = loginToken("filter-owner-b", "secret-password-456");

        String collection = "notes_filter_scope_" + DbUtils.id();
        seedOwnerCollection(collection);

        createNote(collection, tokenA, "NOTE-OF-A");
        createNote(collection, tokenB, "SECRET-OF-B");

        TestResponse response = TestRequest.get(
                        "/api/collections/" + collection + "?offset=0&limit=25&filter=title:eq:SECRET-OF-B")
                .withHeader("Authorization", "Bearer " + tokenA)
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), not(containsString("SECRET-OF-B")));
        assertThat(response.getContent(), containsString("\"total\":0"));
        assertThat(response.getContent(), containsString("\"items\":[]"));
    }

    // 2. A LOCKED list rule stays 403 even with a filter present.
    @Test
    void filterOnLockedCollectionStillForbidden() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("filter-locked-user", null, "secret-password-123");
        String token = loginToken("filter-locked-user", "secret-password-123");

        String collection = "notes_filter_locked_" + DbUtils.id();
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());
        TenantTestUtils.seedRecord(collection, "hidden");

        TestResponse response = TestRequest.get(
                        "/api/collections/" + collection + "?offset=0&limit=25&filter=title:eq:hidden")
                .withHeader("Authorization", "Bearer " + token)
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
    }

    // 3. total must reflect the filter, not just items.
    @Test
    void totalRespectsFilter() {
        String collection = "notes_filter_total_" + DbUtils.id();
        seedPublicCollection(collection);
        TenantTestUtils.seedRecord(collection, "keep");
        TenantTestUtils.seedRecord(collection, "drop");
        TenantTestUtils.seedRecord(collection, "drop");

        TestResponse response = TestRequest.get(
                        "/api/collections/" + collection + "?offset=0&limit=25&filter=title:eq:keep")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), containsString("\"total\":1"));
        assertThat(response.getContent(), containsString("keep"));
        assertThat(response.getContent(), not(containsString("drop")));
    }

    // 4. Unknown field is a 400, not a silently swallowed filter.
    @Test
    void unknownFieldReturnsBadRequest() {
        String collection = "notes_filter_unknown_" + DbUtils.id();
        seedPublicCollection(collection);

        TestResponse response = TestRequest.get(
                        "/api/collections/" + collection + "?offset=0&limit=25&filter=nope:eq:x")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("Unknown filter field: nope"));
    }

    // 5. Boolean and number conversion: a plain string compare would never match the stored value.
    @Test
    void booleanFilterMatchesStoredBoolean() {
        String collection = "flags_filter_bool_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("is_premium", FieldType.BOOLEAN, false, true, null)));

        insert(collection, new Document().append("id", DbUtils.id()).append("title", "PREMIUM").append("is_premium", true));
        insert(collection, new Document().append("id", DbUtils.id()).append("title", "FREE").append("is_premium", false));

        TestResponse response = TestRequest.get(
                        "/api/collections/" + collection + "?offset=0&limit=25&filter=is_premium:eq:true")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), containsString("\"total\":1"));
        assertThat(response.getContent(), containsString("PREMIUM"));
        assertThat(response.getContent(), not(containsString("FREE")));
    }

    @Test
    void invalidBooleanReturnsBadRequest() {
        String collection = "flags_filter_bool_bad_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("is_premium", FieldType.BOOLEAN, false, true, null)));

        TestResponse response = TestRequest.get(
                        "/api/collections/" + collection + "?offset=0&limit=25&filter=is_premium:eq:yes")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
    }

    @Test
    void numberFilterMatchesStoredNumber() {
        String collection = "items_filter_num_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("qty", FieldType.NUMBER, false, true, null)));

        insert(collection, new Document().append("id", DbUtils.id()).append("title", "TEN").append("qty", 10));
        insert(collection, new Document().append("id", DbUtils.id()).append("title", "TWENTY").append("qty", 20));

        TestResponse response = TestRequest.get(
                        "/api/collections/" + collection + "?offset=0&limit=25&filter=qty:eq:10")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), containsString("\"total\":1"));
        assertThat(response.getContent(), containsString("TEN"));
        assertThat(response.getContent(), not(containsString("TWENTY")));
    }

    @Test
    void invalidNumberReturnsBadRequest() {
        String collection = "items_filter_num_bad_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("qty", FieldType.NUMBER, false, true, null)));

        TestResponse response = TestRequest.get(
                        "/api/collections/" + collection + "?offset=0&limit=25&filter=qty:eq:abc")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
    }

    // 6. JSON and FILE are not filterable.
    @Test
    void jsonFieldReturnsBadRequest() {
        String collection = "docs_filter_json_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("payload", FieldType.JSON, false, true, null)));

        TestResponse response = TestRequest.get(
                        "/api/collections/" + collection + "?offset=0&limit=25&filter=payload:eq:x")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("not filterable"));
    }

    @Test
    void fileFieldReturnsBadRequest() {
        String collection = "docs_filter_file_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("attachment", FieldType.FILE, false, true, null)));

        TestResponse response = TestRequest.get(
                        "/api/collections/" + collection + "?offset=0&limit=25&filter=attachment:eq:x")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("not filterable"));
    }

    // 7. No filter: behavior is unchanged.
    @Test
    void withoutFilterBehaviorIsUnchanged() {
        String collection = "notes_filter_none_" + DbUtils.id();
        seedPublicCollection(collection);
        TenantTestUtils.seedRecord(collection, "A");
        TenantTestUtils.seedRecord(collection, "B");

        TestResponse response = TestRequest.get(
                        "/api/collections/" + collection + "?offset=0&limit=25")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), containsString("\"total\":2"));
        assertThat(response.getContent(), containsString("A"));
        assertThat(response.getContent(), containsString("B"));

        // An empty filter parameter must behave identically to no filter at all.
        TestResponse empty = TestRequest.get(
                        "/api/collections/" + collection + "?offset=0&limit=25&filter=")
                .execute();
        assertThat(empty.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(empty.getContent(), containsString("\"total\":2"));
    }

    // 8. A value containing a colon and a URL-encoded value are parsed correctly.
    @Test
    void valueWithColonAndUrlEncodingIsParsed() {
        String collection = "users_filter_colon_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("apple_sub", FieldType.STRING, false, true, null)));

        String value = "001234.ab:cd ef";
        insert(collection, new Document().append("id", DbUtils.id()).append("title", "APPLE").append("apple_sub", value));
        insert(collection, new Document().append("id", DbUtils.id()).append("title", "OTHER").append("apple_sub", "999"));

        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
        TestResponse response = TestRequest.get(
                        "/api/collections/" + collection + "?offset=0&limit=25&filter=apple_sub:eq:" + encoded)
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), containsString("\"total\":1"));
        assertThat(response.getContent(), containsString("APPLE"));
        assertThat(response.getContent(), not(containsString("OTHER")));
    }

    // 9. Admin bypass stores Filters.empty() as the list filter; anding a client filter must work.
    @Test
    void adminBypassWithFilterWorks() {
        String collection = "notes_filter_admin_" + DbUtils.id();
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());
        TenantTestUtils.seedRecord(collection, "keep");
        TenantTestUtils.seedRecord(collection, "drop");

        AdminTestUtils.AdminCookies adminCookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
        TestResponse response = AdminTestUtils.getWithAdminCookies(
                "/api/collections/" + collection + "?offset=0&limit=25&filter=title:eq:keep",
                adminCookies);

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), containsString("\"total\":1"));
        assertThat(response.getContent(), containsString("keep"));
        assertThat(response.getContent(), not(containsString("drop")));
    }

    // 10. Pagination applies to the filtered set.
    @Test
    void paginationAppliesToFilteredSet() {
        String collection = "items_filter_page_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("kind", FieldType.STRING, false, true, null)));

        for (int i = 0; i < 5; i++) {
            insert(collection, new Document().append("id", DbUtils.id()).append("title", "match-" + i).append("kind", "keep"));
        }
        for (int i = 0; i < 3; i++) {
            insert(collection, new Document().append("id", DbUtils.id()).append("title", "other-" + i).append("kind", "drop"));
        }

        TestResponse response = TestRequest.get(
                        "/api/collections/" + collection + "?offset=1&limit=2&filter=kind:eq:keep")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        // total is the filtered count, the page carries at most limit items.
        assertThat(response.getContent(), containsString("\"total\":5"));
        assertThat(response.getContent(), not(containsString("drop")));
    }

    private void insert(String collection, Document document) {
        Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), collection)
                .insertOne(document);
    }

    private void seedPublicCollection(String collection) {
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null)));
    }

    private void seedOwnerCollection(String collection) {
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("owner", "owner", "auth", "owner", "owner", "owner"),
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition(
                                "owner",
                                FieldType.RELATION,
                                false,
                                true,
                                FieldOptions.forRelation("users"))));
    }

    private void createNote(String collection, String token, String title) {
        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withHeader("Authorization", "Bearer " + token)
                .withStringBody("{\"title\":\"" + title + "\"}")
                .withContentType("application/json")
                .execute();
        assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));
    }

    private String loginToken(String username, String password) {
        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(username, password))
                .withContentType("application/json")
                .execute();
        return extractJsonString(login.getContent(), "accessToken");
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
