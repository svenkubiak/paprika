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
import utils.DbUtils;
import utils.TenantTestUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@ExtendWith({TestRunner.class})
class CollectionOwnerIntegrationTest {

    @Test
    void ownerRulesScopeRecordsToAuthenticatedUser() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("owner-a", null, "secret-password-123");
        userService.createUser("owner-b", null, "secret-password-456");

        String tokenA = loginToken("owner-a", "secret-password-123");
        String tokenB = loginToken("owner-b", "secret-password-456");

        String collection = "notes_owner_" + DbUtils.id();
        seedOwnerCollection(collection);

        TestResponse createA = TestRequest.post("/api/collections/" + collection)
                .withHeader("Authorization", "Bearer " + tokenA)
                .withStringBody("{\"title\":\"note from A\"}")
                .withContentType("application/json")
                .execute();
        assertThat(createA.getStatusCode(), equalTo(StatusCodes.CREATED));

        TestResponse createB = TestRequest.post("/api/collections/" + collection)
                .withHeader("Authorization", "Bearer " + tokenB)
                .withStringBody("{\"title\":\"note from B\"}")
                .withContentType("application/json")
                .execute();
        assertThat(createB.getStatusCode(), equalTo(StatusCodes.CREATED));

        TestResponse listA = TestRequest.get("/api/collections/" + collection + "?offset=0&limit=25")
                .withHeader("Authorization", "Bearer " + tokenA)
                .execute();
        assertThat(listA.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(listA.getContent(), containsString("\"title\":\"note from A\""));
        assertThat(listA.getContent(), not(containsString("\"title\":\"note from B\"")));

        TestResponse listB = TestRequest.get("/api/collections/" + collection + "?offset=0&limit=25")
                .withHeader("Authorization", "Bearer " + tokenB)
                .execute();
        assertThat(listB.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(listB.getContent(), containsString("\"title\":\"note from B\""));
        assertThat(listB.getContent(), not(containsString("\"title\":\"note from A\"")));
    }

    @Test
    void createRejectsSpoofedOwnerUserId() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("owner-spoof", null, "secret-password-123");
        userService.createUser("owner-victim", null, "secret-password-456");

        String token = loginToken("owner-spoof", "secret-password-123");
        String victimId = findUserId("owner-victim");

        String collection = "notes_spoof_" + DbUtils.id();
        seedOwnerCollection(collection);

        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withHeader("Authorization", "Bearer " + token)
                .withStringBody("{\"title\":\"stolen\",\"owner\":\"" + victimId + "\"}")
                .withContentType("application/json")
                .execute();

        assertThat(create.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
    }

    @Test
    void listIgnoresQueryParameterNamedLikeARouteParameter() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("bypass-a", null, "secret-password-123");
        userService.createUser("bypass-b", null, "secret-password-456");

        String tokenA = loginToken("bypass-a", "secret-password-123");
        String tokenB = loginToken("bypass-b", "secret-password-456");

        String collection = "notes_bypass_" + DbUtils.id();
        seedOwnerCollection(collection);

        String idA = createNote(collection, tokenA, "NOTE-OF-A");
        createNote(collection, tokenB, "SECRET-OF-B");

        // ?id= must stay an ordinary query parameter: the list route declares no id, so it may
        // never downgrade the LIST rule into a per-record VIEW check.
        TestResponse bypass = TestRequest.get(
                        "/api/collections/" + collection + "?offset=0&limit=25&id=" + idA)
                .withHeader("Authorization", "Bearer " + tokenA)
                .execute();

        assertThat(bypass.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(bypass.getContent(), containsString("NOTE-OF-A"));
        assertThat(bypass.getContent(), not(containsString("SECRET-OF-B")));
        assertThat(bypass.getContent(), containsString("\"total\":1"));
    }

    @Test
    void listIgnoresQueryParameterNamedField() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("field-a", null, "secret-password-123");
        userService.createUser("field-b", null, "secret-password-456");

        String tokenA = loginToken("field-a", "secret-password-123");
        String tokenB = loginToken("field-b", "secret-password-456");

        String collection = "notes_field_" + DbUtils.id();
        seedOwnerCollection(collection);

        createNote(collection, tokenA, "NOTE-OF-A");
        createNote(collection, tokenB, "SECRET-OF-B");

        TestResponse list = TestRequest.get(
                        "/api/collections/" + collection + "?offset=0&limit=25&field=title")
                .withHeader("Authorization", "Bearer " + tokenA)
                .execute();

        assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(list.getContent(), containsString("NOTE-OF-A"));
        assertThat(list.getContent(), not(containsString("SECRET-OF-B")));
        assertThat(list.getContent(), containsString("\"total\":1"));
    }

    @Test
    void anonymousCreateIsRejectedOnOwnerCollection() {
        String collection = "notes_anoncreate_" + DbUtils.id();
        // create = owner as well, so an anonymous create is checked against the owner rule itself
        // rather than against the auth shorthand.
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("owner", "owner", "owner", "owner", "owner", "owner"),
                java.util.List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition(
                                "owner",
                                FieldType.RELATION,
                                false,
                                true,
                                FieldOptions.forRelation("users"))));

        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withStringBody("{\"title\":\"anonymous\"}")
                .withContentType("application/json")
                .execute();

        assertThat(create.getStatusCode(), not(equalTo(StatusCodes.CREATED)));
        assertThat(create.getStatusCode(), isOneOf(StatusCodes.UNAUTHORIZED, StatusCodes.FORBIDDEN));
    }

    @Test
    void anonymousRequestsCannotReachRecordsWithoutOwner() {
        String collection = "notes_ownerless_" + DbUtils.id();
        seedOwnerCollection(collection);

        // Records created through the admin UI carry no owner field, since create() skips owner
        // stamping on admin bypass.
        String recordId = DbUtils.id();
        Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), collection)
                .insertOne(new org.bson.Document()
                        .append("id", recordId)
                        .append("title", "SECRET"));

        TestResponse read = TestRequest.get("/api/collections/" + collection + "/" + recordId).execute();
        assertThat(read.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));

        TestResponse update = TestRequest.patch("/api/collections/" + collection + "/" + recordId)
                .withStringBody("{\"title\":\"overwritten\"}")
                .withContentType("application/json")
                .execute();
        assertThat(update.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));

        TestResponse delete = TestRequest.delete("/api/collections/" + collection + "/" + recordId).execute();
        assertThat(delete.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));

        TestResponse list = TestRequest.get("/api/collections/" + collection + "?offset=0&limit=25").execute();
        assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(list.getContent(), not(containsString("SECRET")));

        org.bson.Document stored = Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), collection)
                .find(com.mongodb.client.model.Filters.eq("id", recordId))
                .first();
        assertThat(stored, notNullValue());
        assertThat(stored.getString("title"), equalTo("SECRET"));
    }

    private String createNote(String collection, String token, String title) {
        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withHeader("Authorization", "Bearer " + token)
                .withStringBody("{\"title\":\"" + title + "\"}")
                .withContentType("application/json")
                .execute();
        assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));

        org.bson.Document stored = Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), collection)
                .find(com.mongodb.client.model.Filters.eq("title", title))
                .first();
        if (stored == null) {
            throw new IllegalStateException("Missing record: " + title);
        }
        return stored.getString("id");
    }

    private void seedOwnerCollection(String collection) {
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("owner", "owner", "auth", "owner", "owner", "owner"),
                java.util.List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition(
                                "owner",
                                FieldType.RELATION,
                                false,
                                true,
                                FieldOptions.forRelation("users"))
                ));
    }

    private String loginToken(String username, String password) {
        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(username, password))
                .withContentType("application/json")
                .execute();
        return extractJsonString(login.getContent(), "accessToken");
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
