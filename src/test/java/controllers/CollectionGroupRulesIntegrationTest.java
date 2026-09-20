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
import models.TenantDefinition;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import services.UserService;
import utils.AdminTestUtils;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.util.List;

import static com.mongodb.client.model.Filters.eq;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * The {@code group} preset: a record belongs to a group, and the caller reaches it only through a
 * membership record in a second collection.
 * <p>
 * The fixture is one team per user plus a third user with no membership at all - the caller that
 * distinguishes a scoping filter from a missing one, because an unscoped list would hand them the
 * whole collection.
 */
@ExtendWith({TestRunner.class})
class CollectionGroupRulesIntegrationTest {
    private static final String MEMBERSHIPS = "grp_memberships";
    private static final String POSTS = "grp_posts";
    private static final String CREW_A = "crew-a";
    private static final String CREW_B = "crew-b";
    private static final String PASSWORD = "secret-password-123";

    private static String userA;
    private static String userB;
    private static String userC;
    private static String tokenA;
    private static String tokenB;
    private static String tokenC;
    private static String postA;
    private static String postB;

    @BeforeAll
    static void seed() {
        TenantTestUtils.seedCollection(MEMBERSHIPS, CollectionRules.locked(), membershipFields());
        TenantTestUtils.seedCollection(POSTS, groupRules(), postFields());

        UserService userService = Application.getInstance(UserService.class);
        userA = id(userService.createUser("grp-user-a", null, PASSWORD));
        userB = id(userService.createUser("grp-user-b", null, PASSWORD));
        userC = id(userService.createUser("grp-user-c", null, PASSWORD));

        tokenA = login("grp-user-a");
        tokenB = login("grp-user-b");
        tokenC = login("grp-user-c");

        addMembership(userA, CREW_A);
        addMembership(userB, CREW_B);

        postA = insertPost("Post of crew A", CREW_A);
        postB = insertPost("Post of crew B", CREW_B);
    }

    @Test
    void listReturnsOnlyTheRecordsOfTheCallersGroup() {
        TestResponse listA = list(tokenA);
        assertThat(listA.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(listA.getContent(), containsString("Post of crew A"));
        assertThat(listA.getContent(), not(containsString("Post of crew B")));

        TestResponse listB = list(tokenB);
        assertThat(listB.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(listB.getContent(), containsString("Post of crew B"));
        assertThat(listB.getContent(), not(containsString("Post of crew A")));
    }

    /**
     * The mistake this preset invites: treating "no membership" as "no filter". The caller has to
     * end up with an empty list, never with the collection.
     */
    @Test
    void aCallerWithoutAnyMembershipSeesNothing() {
        TestResponse list = list(tokenC);

        assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(list.getContent(), containsString("\"total\":0"));
        assertThat(list.getContent(), not(containsString("Post of crew A")));
        assertThat(list.getContent(), not(containsString("Post of crew B")));
    }

    /** A client filter narrows the result further; it cannot reach outside the caller's group. */
    @Test
    void aClientFilterNarrowsButNeverWidensTheScope() {
        TestResponse own = TestRequest
                .get("/api/collections/" + POSTS + "?offset=0&limit=50&filter=crew:eq:" + CREW_A)
                .withHeader("Authorization", "Bearer " + tokenA)
                .execute();
        assertThat(own.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(own.getContent(), containsString("Post of crew A"));

        // The filter asks for the other group, the rule still decides: anded, never substituted.
        TestResponse filteredToForeignGroup = TestRequest
                .get("/api/collections/" + POSTS + "?offset=0&limit=50&filter=crew:eq:" + CREW_B)
                .withHeader("Authorization", "Bearer " + tokenA)
                .execute();
        assertThat(filteredToForeignGroup.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(filteredToForeignGroup.getContent(), containsString("\"total\":0"));
        assertThat(filteredToForeignGroup.getContent(), not(containsString("Post of crew B")));
    }

    @Test
    void viewAndDeleteOfAForeignRecordAreRefused() {
        TestResponse view = TestRequest.get("/api/collections/" + POSTS + "/" + postB)
                .withHeader("Authorization", "Bearer " + tokenA)
                .execute();
        assertThat(view.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
        assertThat(view.getContent(), not(containsString("Post of crew B")));

        TestResponse delete = TestRequest.delete("/api/collections/" + POSTS + "/" + postB)
                .withHeader("Authorization", "Bearer " + tokenA)
                .execute();
        assertThat(delete.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));

        TestResponse ownView = TestRequest.get("/api/collections/" + POSTS + "/" + postA)
                .withHeader("Authorization", "Bearer " + tokenA)
                .execute();
        assertThat(ownView.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(ownView.getContent(), containsString("Post of crew A"));
    }

    @Test
    void createIsAllowedIntoTheOwnGroupOnly() {
        TestResponse own = create(tokenA, "{\"title\":\"New A\",\"crew\":\"" + CREW_A + "\"}");
        assertThat(own.getStatusCode(), equalTo(StatusCodes.CREATED));

        TestResponse foreign = create(tokenA, "{\"title\":\"New B\",\"crew\":\"" + CREW_B + "\"}");
        assertThat(foreign.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));

        // Nothing is filled in on the client's behalf: without the group there is nothing to check
        TestResponse withoutGroup = create(tokenA, "{\"title\":\"No crew\"}");
        assertThat(withoutGroup.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));

        TestResponse withoutMembership = create(tokenC, "{\"title\":\"New C\",\"crew\":\"" + CREW_A + "\"}");
        assertThat(withoutMembership.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
    }

    /** An update must not move a record into a group the caller does not belong to. */
    @Test
    void aRecordCannotBeMovedIntoAForeignGroup() {
        String record = insertPost("Movable", CREW_A);

        TestResponse moved = TestRequest.patch("/api/collections/" + POSTS + "/" + record)
                .withHeader("Authorization", "Bearer " + tokenA)
                .withStringBody("{\"crew\":\"" + CREW_B + "\"}")
                .withContentType("application/json")
                .execute();
        assertThat(moved.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));

        assertThat(stored(record).getString("crew"), equalTo(CREW_A));

        TestResponse renamed = TestRequest.patch("/api/collections/" + POSTS + "/" + record)
                .withHeader("Authorization", "Bearer " + tokenA)
                .withStringBody("{\"title\":\"Renamed\"}")
                .withContentType("application/json")
                .execute();
        assertThat(renamed.getStatusCode(), equalTo(StatusCodes.OK));
    }

    @Test
    void anUnauthenticatedCallerNeverSeesData() {
        TestResponse list = TestRequest.get("/api/collections/" + POSTS).execute();
        assertThat(list.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat(list.getContent(), not(containsString("Post of crew A")));

        TestResponse view = TestRequest.get("/api/collections/" + POSTS + "/" + postA).execute();
        assertThat(view.getStatusCode(), anyOf(
                equalTo(StatusCodes.UNAUTHORIZED),
                equalTo(StatusCodes.FORBIDDEN),
                equalTo(StatusCodes.NOT_FOUND)));
        assertThat(view.getContent(), not(containsString("Post of crew A")));

        TestResponse create = TestRequest.post("/api/collections/" + POSTS)
                .withStringBody("{\"title\":\"guest\",\"crew\":\"" + CREW_A + "\"}")
                .withContentType("application/json")
                .execute();
        assertThat(create.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
    }

    /**
     * There is no cache beyond the request: a membership that is revoked has to stop granting
     * access on the very next call, not when some window runs out.
     */
    @Test
    void revokingAMembershipTakesEffectOnTheNextRequest() {
        UserService userService = Application.getInstance(UserService.class);
        String userId = id(userService.createUser("grp-user-revoked", null, PASSWORD));
        String token = login("grp-user-revoked");
        String membership = addMembership(userId, CREW_A);

        TestResponse before = list(token);
        assertThat(before.getContent(), containsString("Post of crew A"));

        Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), MEMBERSHIPS)
                .deleteOne(eq("id", membership));

        TestResponse after = list(token);
        assertThat(after.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(after.getContent(), containsString("\"total\":0"));
        assertThat(after.getContent(), not(containsString("Post of crew A")));

        TestResponse view = TestRequest.get("/api/collections/" + POSTS + "/" + postA)
                .withHeader("Authorization", "Bearer " + token)
                .execute();
        assertThat(view.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
    }

    /** The bypass is untouched by all of this: it skips the rules, membership ones included. */
    @Test
    void theAdminSessionAndABypassingKeySeeEverything() {
        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
        TestResponse admin = AdminTestUtils.getWithAdminCookies(
                "/api/collections/" + POSTS + "?offset=0&limit=50", cookies);
        assertThat(admin.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(admin.getContent(), containsString("Post of crew A"));
        assertThat(admin.getContent(), containsString("Post of crew B"));

        String key = createBypassKey(userC);
        TestResponse withKey = TestRequest.get("/api/collections/" + POSTS + "?offset=0&limit=50")
                .withHeader("Authorization", "Bearer " + key)
                .execute();
        assertThat(withKey.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(withKey.getContent(), containsString("Post of crew A"));
        assertThat(withKey.getContent(), containsString("Post of crew B"));
    }

    private static TestResponse list(String token) {
        return TestRequest.get("/api/collections/" + POSTS + "?offset=0&limit=50")
                .withHeader("Authorization", "Bearer " + token)
                .execute();
    }

    private static TestResponse create(String token, String body) {
        return TestRequest.post("/api/collections/" + POSTS)
                .withHeader("Authorization", "Bearer " + token)
                .withStringBody(body)
                .withContentType("application/json")
                .execute();
    }

    private static Document stored(String recordId) {
        return Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), POSTS)
                .find(eq("id", recordId))
                .first();
    }

    static CollectionRules groupRules() {
        return new CollectionRules(
                "group", "group", "group", "group", "group",
                "owner", MEMBERSHIPS, "user", "crew", "crew");
    }

    static List<FieldDefinition> membershipFields() {
        return List.of(
                new FieldDefinition("user", FieldType.RELATION, true, false, FieldOptions.forRelation("users")),
                new FieldDefinition("crew", FieldType.STRING, true, false, null));
    }

    static List<FieldDefinition> postFields() {
        return List.of(
                new FieldDefinition("title", FieldType.STRING, true, false, null),
                new FieldDefinition("crew", FieldType.STRING, true, false, null));
    }

    static String addMembership(String userId, String group) {
        String id = DbUtils.id();
        Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), MEMBERSHIPS)
                .insertOne(new Document()
                        .append("id", id)
                        .append("user", userId)
                        .append("crew", group));
        return id;
    }

    private static String insertPost(String title, String group) {
        String id = DbUtils.id();
        Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), POSTS)
                .insertOne(new Document()
                        .append("id", id)
                        .append("title", title)
                        .append("crew", group));
        return id;
    }

    static String login(String username) {
        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("default", username, PASSWORD))
                .withContentType("application/json")
                .execute();
        assertThat(login.getStatusCode(), equalTo(StatusCodes.OK));
        return extractJsonString(login.getContent(), "accessToken");
    }

    private static String createBypassKey(String userId) {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        TestResponse response = AdminTestUtils.postWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/api-keys",
                new AdminTestUtils.AdminCookies(AdminTestUtils.loginAsAdmin(), null),
                "{\"name\":\"grp-bypass\",\"userId\":\"" + userId + "\",\"bypassRules\":true}",
                "application/json");
        assertThat(response.getStatusCode(), equalTo(StatusCodes.CREATED));
        return extractJsonString(response.getContent(), "key");
    }

    static String id(java.util.Map<String, Object> user) {
        return String.valueOf(user.get("id"));
    }

    static String extractJsonString(String json, String field) {
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
