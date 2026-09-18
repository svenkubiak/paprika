package controllers;

import auth.TenantContext;
import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionDefinition;
import models.CollectionRules;
import models.FieldDefinition;
import models.TenantDefinition;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import services.UserService;
import utils.AdminTestUtils;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * On the {@code users} collection, {@code Own records} has to mean "my own account"
 * ({@code record.id = auth.id}) instead of the owner-field comparison every other collection uses
 * - a user record has no relation pointing at itself, so the owner variant would lock every user
 * out of their own profile.
 */
@ExtendWith({TestRunner.class})
class UsersOwnRecordsIntegrationTest {

    private static final CollectionRules OWN_RECORDS =
            new CollectionRules("owner", "owner", "owner", "owner", "owner", "owner");

    @Test
    void viewReachesTheOwnAccountAndNoOther() {
        withUsersRules(OWN_RECORDS, () -> {
            UserService userService = Application.getInstance(UserService.class);
            String meId = userId(userService.createUser("own-view-me", null, "secret-password-123"));
            String otherId = userId(userService.createUser("own-view-other", null, "secret-password-123"));
            String token = accessTokenFor("own-view-me");

            TestResponse own = get("/api/collections/users/" + meId, token);
            assertThat(own.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(own.getContent(), containsString("own-view-me"));

            TestResponse foreign = get("/api/collections/users/" + otherId, token);
            assertThat(foreign.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
            assertThat(foreign.getContent(), not(containsString("own-view-other")));
        });
    }

    @Test
    void updateChangesTheOwnAccountAndNoOther() {
        withUsersRules(OWN_RECORDS, () -> {
            UserService userService = Application.getInstance(UserService.class);
            String meId = userId(userService.createUser("own-update-me", null, "secret-password-123"));
            String otherId = userId(userService.createUser("own-update-other", null, "secret-password-123"));
            String token = accessTokenFor("own-update-me");

            TestResponse own = patch("/api/collections/users/" + meId, token,
                    "{\"email\":\"own-update-me@example.com\"}");
            assertThat(own.getStatusCode(), equalTo(StatusCodes.OK));

            TestResponse readBack = get("/api/collections/users/" + meId, token);
            assertThat(readBack.getContent(), containsString("own-update-me@example.com"));

            TestResponse foreign = patch("/api/collections/users/" + otherId, token,
                    "{\"email\":\"hijacked@example.com\"}");
            assertThat(foreign.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));

            Document stored = userRecord(otherId);
            assertThat(stored.getString("email"), nullValue());
        });
    }

    @Test
    void listReturnsExactlyTheOwnAccount() {
        withUsersRules(OWN_RECORDS, () -> {
            UserService userService = Application.getInstance(UserService.class);
            userService.createUser("own-list-me", null, "secret-password-123");
            userService.createUser("own-list-other", null, "secret-password-123");
            String token = accessTokenFor("own-list-me");

            // This path goes through RuleToMongoConverter rather than RuleEvaluator
            TestResponse list = get("/api/collections/users?offset=0&limit=25", token);

            assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(list.getContent(), containsString("own-list-me"));
            assertThat(list.getContent(), not(containsString("own-list-other")));
            assertThat(list.getContent(), containsString("\"total\":1"));
        });
    }

    @Test
    void deleteRemovesTheOwnAccountAndNoOther() {
        withUsersRules(OWN_RECORDS, () -> {
            UserService userService = Application.getInstance(UserService.class);
            String meId = userId(userService.createUser("own-delete-me", null, "secret-password-123"));
            String otherId = userId(userService.createUser("own-delete-other", null, "secret-password-123"));
            String token = accessTokenFor("own-delete-me");

            TestResponse foreign = TestRequest.delete("/api/collections/users/" + otherId)
                    .withHeader("Authorization", "Bearer " + token)
                    .execute();
            assertThat(foreign.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
            assertThat(userRecord(otherId), notNullValue());

            TestResponse own = TestRequest.delete("/api/collections/users/" + meId)
                    .withHeader("Authorization", "Bearer " + token)
                    .execute();
            assertThat(own.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(userRecord(meId), nullValue());
        });
    }

    @Test
    void createIsNeverGrantedByOwnRecordsOnUsers() {
        withUsersRules(OWN_RECORDS, () -> {
            UserService userService = Application.getInstance(UserService.class);
            String meId = userId(userService.createUser("own-create-me", null, "secret-password-123"));
            String token = accessTokenFor("own-create-me");

            // There is no record yet whose id could equal the caller's, so Own records cannot be
            // satisfied - not even by sending one's own id along. Sign-up is /api/auth/register.
            TestResponse plain = TestRequest.post("/api/collections/users")
                    .withHeader("Authorization", "Bearer " + token)
                    .withStringBody("{\"username\":\"own-create-new\",\"password\":\"another-secret-123\"}")
                    .withContentType("application/json")
                    .execute();
            assertThat(plain.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));

            TestResponse withOwnId = TestRequest.post("/api/collections/users")
                    .withHeader("Authorization", "Bearer " + token)
                    .withStringBody("{\"id\":\"" + meId + "\",\"username\":\"own-create-spoof\","
                            + "\"password\":\"another-secret-123\"}")
                    .withContentType("application/json")
                    .execute();
            assertThat(withOwnId.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));

            assertThat(usernameExists("own-create-new"), is(false));
            assertThat(usernameExists("own-create-spoof"), is(false));
        });
    }

    @Test
    void noOwnerFieldIsWrittenIntoAUserRecord() {
        UserService userService = Application.getInstance(UserService.class);
        String meId = userId(userService.createUser("own-noleak-me", null, "secret-password-123"));

        withUsersRules(OWN_RECORDS, () -> {
            String token = accessTokenFor("own-noleak-me");

            TestResponse update = patch("/api/collections/users/" + meId, token,
                    "{\"email\":\"own-noleak@example.com\"}");
            assertThat(update.getStatusCode(), equalTo(StatusCodes.OK));

            Document stored = userRecord(meId);
            assertThat("the owner field plays no part on users",
                    stored.containsKey("owner"), is(false));
            assertThat(get("/api/collections/users/" + meId, token).getContent(),
                    not(containsString("\"owner\"")));
        });

        // Also for a create through the data plane, which needs an open create rule
        withUsersRules(new CollectionRules("owner", "owner", "auth", "owner", "owner", "owner"), () -> {
            String token = accessTokenFor("own-noleak-me");

            TestResponse create = TestRequest.post("/api/collections/users")
                    .withHeader("Authorization", "Bearer " + token)
                    .withStringBody("{\"username\":\"own-noleak-created\",\"password\":\"another-secret-123\"}")
                    .withContentType("application/json")
                    .execute();
            assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));

            Document created = Application.getInstance(TenantCollectionService.class)
                    .dataCollection(TenantTestUtils.defaultTenantContext(), "users")
                    .find(new Document("username", "own-noleak-created"))
                    .first();
            assertThat(created, notNullValue());
            assertThat(created.containsKey("owner"), is(false));
        });
    }

    @Test
    void guestGetsNothingFromOwnRecordsOnUsers() {
        withUsersRules(OWN_RECORDS, () -> {
            UserService userService = Application.getInstance(UserService.class);
            String meId = userId(userService.createUser("own-guest-target", null, "secret-password-123"));

            // Same shape as an owner rule on any other collection: the list is answered, but the
            // filter can never match for a caller without an identity
            TestResponse list = TestRequest.get("/api/collections/users?offset=0&limit=25").execute();
            assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(list.getContent(), containsString("\"total\":0"));
            assertThat(list.getContent(), not(containsString("own-guest-target")));

            TestResponse view = TestRequest.get("/api/collections/users/" + meId).execute();
            assertThat(view.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
            assertThat(view.getContent(), not(containsString("own-guest-target")));
        });
    }

    @Test
    void adminSessionAndBypassKeyStillSeeEveryUser() {
        withUsersRules(OWN_RECORDS, () -> {
            UserService userService = Application.getInstance(UserService.class);
            String boundId = userId(userService.createUser("own-bypass-me", null, "secret-password-123"));
            userService.createUser("own-bypass-other", null, "secret-password-123");
            TenantDefinition tenant = TenantTestUtils.defaultTenant();

            AdminTestUtils.AdminCookies adminCookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
            TestResponse asAdmin = AdminTestUtils.getWithAdminCookies(
                    "/api/collections/users?offset=0&limit=25", adminCookies);
            assertThat(asAdmin.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(asAdmin.getContent(), containsString("own-bypass-me"));
            assertThat(asAdmin.getContent(), containsString("own-bypass-other"));

            TestResponse keyResponse = AdminTestUtils.postWithAdminCookies(
                    "/api/meta/tenants/" + tenant.id() + "/api-keys",
                    new AdminTestUtils.AdminCookies(AdminTestUtils.loginAsAdmin(), null),
                    "{\"name\":\"own-records-bypass\",\"userId\":\"" + boundId + "\",\"bypassRules\":true}",
                    "application/json");
            assertThat(keyResponse.getStatusCode(), equalTo(StatusCodes.CREATED));
            String key = extractJsonString(keyResponse.getContent(), "key");

            TestResponse asKey = get("/api/collections/users?offset=0&limit=25", key);
            assertThat(asKey.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(asKey.getContent(), containsString("own-bypass-me"));
            assertThat(asKey.getContent(), containsString("own-bypass-other"));
        });
    }

    @Test
    void ownerRuleOnAnOrdinaryCollectionIsUnchanged() {
        UserService userService = Application.getInstance(UserService.class);
        String meId = userId(userService.createUser("own-regression-me", null, "secret-password-123"));
        String otherId = userId(userService.createUser("own-regression-other", null, "secret-password-123"));
        String token = accessTokenFor("own-regression-me");

        String collection = "notes_own_records_regression";
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        collections.insertDefinition(ctx, new CollectionDefinition(
                DbUtils.id(),
                collection,
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("author", FieldType.RELATION, false, true,
                                models.FieldOptions.forRelation("users"))),
                List.of(),
                new CollectionRules("owner", "owner", "auth", "owner", "owner", "author"),
                false));

        collections.dataCollection(ctx, collection).insertOne(new Document()
                .append("id", DbUtils.id()).append("title", "mine").append("author", meId));
        collections.dataCollection(ctx, collection).insertOne(new Document()
                .append("id", DbUtils.id()).append("title", "theirs").append("author", otherId));

        TestResponse list = get("/api/collections/" + collection + "?offset=0&limit=25", token);
        assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(list.getContent(), containsString("mine"));
        assertThat(list.getContent(), not(containsString("theirs")));
        assertThat(list.getContent(), containsString("\"total\":1"));

        // The owner field is still filled in automatically on a create
        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withHeader("Authorization", "Bearer " + token)
                .withStringBody("{\"title\":\"created by me\"}")
                .withContentType("application/json")
                .execute();
        assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));

        Document created = collections.dataCollection(ctx, collection)
                .find(new Document("title", "created by me"))
                .first();
        assertThat(created, notNullValue());
        assertThat(created.getString("author"), equalTo(meId));
    }

    private static void withUsersRules(CollectionRules rules, Runnable body) {
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        CollectionDefinition original = collections.findDefinition(ctx, "users");

        collections.replaceDefinition(ctx, new CollectionDefinition(
                original.id(),
                original.name(),
                original.fields(),
                original.indexes(),
                rules,
                original.system()));

        try {
            body.run();
        } finally {
            collections.replaceDefinition(ctx, original);
        }
    }

    private static Document userRecord(String userId) {
        return Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), "users")
                .find(new Document("id", userId))
                .first();
    }

    private static boolean usernameExists(String username) {
        return Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), "users")
                .find(new Document("username", username))
                .first() != null;
    }

    private static TestResponse get(String path, String bearer) {
        return TestRequest.get(path).withHeader("Authorization", "Bearer " + bearer).execute();
    }

    private static TestResponse patch(String path, String bearer, String body) {
        return TestRequest.patch(path)
                .withHeader("Authorization", "Bearer " + bearer)
                .withStringBody(body)
                .withContentType("application/json")
                .execute();
    }

    private static String accessTokenFor(String username) {
        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("default", username, "secret-password-123"))
                .withContentType("application/json")
                .execute();
        assertThat(login.getStatusCode(), equalTo(StatusCodes.OK));
        return extractJsonString(login.getContent(), "accessToken");
    }

    private static String userId(Map<String, Object> user) {
        return String.valueOf(user.get("id"));
    }

    private static String extractJsonString(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("Missing " + field + " in: " + json);
        }
        start += marker.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
