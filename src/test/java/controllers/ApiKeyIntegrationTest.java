package controllers;

import auth.TenantContext;
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
import services.TenantService;
import services.TenantUserService;
import services.UserService;
import utils.AdminTestUtils;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Covers API keys: a second way to prove an existing tenant-user identity. A key must behave
 * exactly like an access token of the bound user, and never more than that.
 */
@ExtendWith({TestRunner.class})
class ApiKeyIntegrationTest {

    @Test
    void keyBehavesExactlyLikeAnAccessTokenOfTheBoundUser() {
        UserService userService = Application.getInstance(UserService.class);
        String ownerId = userId(userService.createUser("apikey-owner", null, "secret-password-123"));
        String otherId = userId(userService.createUser("apikey-other-owner", null, "secret-password-123"));

        String collection = "posts_apikey_owner_test";
        seedOwnedCollection(collection);
        seedOwnedRecord(collection, "mine", ownerId);
        seedOwnedRecord(collection, "not mine", otherId);

        String key = createKey("apikey-owner-key", ownerId).key();
        String token = accessTokenFor("apikey-owner");

        TestResponse withToken = TestRequest.get("/api/collections/" + collection + "?offset=0&limit=25")
                .withHeader("Authorization", "Bearer " + token)
                .execute();
        TestResponse withKey = TestRequest.get("/api/collections/" + collection + "?offset=0&limit=25")
                .withHeader("Authorization", "Bearer " + key)
                .execute();

        assertThat(withToken.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(withKey.getStatusCode(), equalTo(withToken.getStatusCode()));
        assertThat(withKey.getContent(), equalTo(withToken.getContent()));
        assertThat(withKey.getContent(), containsString("mine"));
        assertThat(withKey.getContent(), not(containsString("not mine")));
    }

    @Test
    void meWithKeyReturnsBoundUser() {
        UserService userService = Application.getInstance(UserService.class);
        String boundId = userId(userService.createUser("apikey-me-user", null, "secret-password-123"));
        String key = createKey("apikey-me-key", boundId).key();

        TestResponse me = TestRequest.get("/api/auth/me")
                .withHeader("Authorization", "Bearer " + key)
                .execute();

        assertThat(me.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(me.getContent(), containsString("apikey-me-user"));
    }

    @Test
    void revokedExpiredAndUnknownKeysAreUnauthorized() {
        UserService userService = Application.getInstance(UserService.class);
        String boundId = userId(userService.createUser("apikey-invalid-user", null, "secret-password-123"));

        String collection = "posts_apikey_invalid_test";
        TenantTestUtils.seedCollection(collection, authListRules());

        CreatedKey revoked = createKey("apikey-revoked", boundId);
        AdminTestUtils.AdminCookies cookies = adminCookies();
        TestResponse revoke = AdminTestUtils.postWithAdminCookies(
                "/api/meta/tenants/" + TenantTestUtils.defaultTenant().id()
                        + "/api-keys/" + revoked.id() + "/revoke",
                cookies,
                "",
                "application/json");
        assertThat(revoke.getStatusCode(), equalTo(StatusCodes.NO_CONTENT));

        String expiredKey = createKey(
                "apikey-expired",
                boundId,
                Instant.now().minus(1, ChronoUnit.DAYS).toString()).key();

        assertThat(listStatus(collection, revoked.key()), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat(listStatus(collection, expiredKey), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat(listStatus(collection, "pk_totally-unknown-key-value-000000000000"), equalTo(StatusCodes.UNAUTHORIZED));
    }

    @Test
    void deletingAKeyRemovesItsRecordAndStopsItWorking() {
        UserService userService = Application.getInstance(UserService.class);
        String boundId = userId(userService.createUser("apikey-delete-user", null, "secret-password-123"));
        TenantDefinition tenant = TenantTestUtils.defaultTenant();

        String collection = "posts_apikey_delete_test";
        TenantTestUtils.seedCollection(collection, authListRules());

        CreatedKey key = createKey("apikey-delete-key", boundId);
        assertThat(listStatus(collection, key.key()), equalTo(StatusCodes.OK));

        String path = "/api/meta/tenants/" + tenant.id() + "/api-keys/" + key.id();
        TestResponse deleted = AdminTestUtils.deleteWithAdminCookies(path, adminCookies());
        assertThat(deleted.getStatusCode(), equalTo(StatusCodes.NO_CONTENT));

        // Unlike revoking, the record is gone as well
        TestResponse list = AdminTestUtils.getWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/api-keys", adminCookies());
        assertThat(list.getContent(), not(containsString("apikey-delete-key")));

        assertThat(listStatus(collection, key.key()), equalTo(StatusCodes.UNAUTHORIZED));

        // Deleting the same key twice is a 404, not a silent success
        TestResponse again = AdminTestUtils.deleteWithAdminCookies(path, adminCookies());
        assertThat(again.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
    }

    @Test
    void revokedKeyKeepsItsRecordUntilItIsDeleted() {
        UserService userService = Application.getInstance(UserService.class);
        String boundId = userId(userService.createUser("apikey-retire-user", null, "secret-password-123"));
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        CreatedKey key = createKey("apikey-retire-key", boundId);

        TestResponse revoke = AdminTestUtils.postWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/api-keys/" + key.id() + "/revoke",
                adminCookies(),
                "",
                "application/json");
        assertThat(revoke.getStatusCode(), equalTo(StatusCodes.NO_CONTENT));

        TestResponse afterRevoke = AdminTestUtils.getWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/api-keys", adminCookies());
        assertThat("a revoked key stays visible, so it remains auditable",
                afterRevoke.getContent(), containsString("apikey-retire-key"));

        assertThat(AdminTestUtils.deleteWithAdminCookies(
                        "/api/meta/tenants/" + tenant.id() + "/api-keys/" + key.id(), adminCookies())
                .getStatusCode(), equalTo(StatusCodes.NO_CONTENT));

        TestResponse afterDelete = AdminTestUtils.getWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/api-keys", adminCookies());
        assertThat(afterDelete.getContent(), not(containsString("apikey-retire-key")));
    }

    @Test
    void keyOfDeletedUserOrInactiveTenantIsUnauthorized() {
        UserService userService = Application.getInstance(UserService.class);
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        String goneId = userId(userService.createUser("apikey-gone-user", null, "secret-password-123"));
        String goneKey = createKey("apikey-gone-key", goneId).key();

        String collection = "posts_apikey_gone_test";
        TenantTestUtils.seedCollection(collection, authListRules());

        Application.getInstance(TenantUserService.class).deleteUser(tenant, goneId);
        assertThat(listStatus(collection, goneKey), equalTo(StatusCodes.UNAUTHORIZED));

        TenantService tenantService = Application.getInstance(TenantService.class);
        TenantDefinition suspended = tenantService.create("ApiKey Suspended", "apikey-suspended-tenant");
        Map<String, Object> suspendedUser = Application.getInstance(TenantUserService.class)
                .createUser(suspended, "apikey-suspended-user", null, "secret-password-123");
        String suspendedKey = createKey(suspended, "apikey-suspended-key", userId(suspendedUser), null).key();

        tenantService.update(suspended.id(), null, null, "inactive", null, null, null, null, null, null, null);
        try {
            TestResponse me = TestRequest.get("/api/auth/me")
                    .withHeader("Authorization", "Bearer " + suspendedKey)
                    .execute();
            assertThat(me.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        } finally {
            tenantService.deleteWithCascade(suspended.id());
        }
    }

    @Test
    void keyCannotReachAnotherTenant() {
        UserService userService = Application.getInstance(UserService.class);
        String boundId = userId(userService.createUser("apikey-tenant-bound", null, "secret-password-123"));
        String key = createKey("apikey-tenant-bound-key", boundId).key();

        TenantService tenantService = Application.getInstance(TenantService.class);
        TenantDefinition other = tenantService.create("ApiKey Other", "apikey-other-tenant");
        String foreignCollection = "posts_apikey_foreign_test";

        try {
            TenantContext foreignCtx = TenantContext.guest(other.id(), other.databaseName());
            TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
            collections.insertDefinition(foreignCtx, new CollectionDefinition(
                    DbUtils.id(),
                    foreignCollection,
                    List.of(new FieldDefinition("title", enums.FieldType.STRING, true, false, null)),
                    List.of(),
                    authListRules(),
                    false));
            collections.dataCollection(foreignCtx, foreignCollection)
                    .insertOne(new Document().append("id", DbUtils.id()).append("title", "foreign record"));

            // The key resolves to its own tenant only, so the other tenant's collection simply
            // does not exist for it.
            TestResponse response = TestRequest.get("/api/collections/" + foreignCollection + "?offset=0&limit=25")
                    .withHeader("Authorization", "Bearer " + key)
                    .execute();

            assertThat(response.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
            assertThat(response.getContent(), not(containsString("foreign record")));
        } finally {
            tenantService.deleteWithCascade(other.id());
        }
    }

    @Test
    void keyNeverGrantsAdminBypassAndCannotBeBoundToSuperadmin() {
        UserService userService = Application.getInstance(UserService.class);
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        String boundId = userId(userService.createUser("apikey-bypass-user", null, "secret-password-123"));
        String key = createKey("apikey-bypass-key", boundId).key();

        String collection = "posts_apikey_locked_test";
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());
        TenantTestUtils.seedRecord(collection, "locked record");

        // Counterpart to adminCookieSentAsBearerDoesNotBypassLockedListRule: an authenticated
        // identity hits the locked rule, it does not bypass it.
        TestResponse locked = TestRequest.get("/api/collections/" + collection + "?offset=0&limit=25")
                .withHeader("Authorization", "Bearer " + key)
                .execute();
        assertThat(locked.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
        assertThat(locked.getContent(), not(containsString("locked record")));

        // A user whose role is not `user` must not get a key at all ...
        String elevatedId = DbUtils.id();
        Application.getInstance(services.TenantDatabaseResolver.class)
                .tenantDatabase(tenant.databaseName())
                .getCollection(constants.CollectionName.tenantData("users"))
                .insertOne(new Document()
                        .append("id", elevatedId)
                        .append("username", "apikey-elevated-user")
                        .append("role", enums.Role.SUPERADMIN));

        TestResponse rejected = AdminTestUtils.postWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/api-keys",
                adminCookies(),
                "{\"name\":\"apikey-elevated\",\"userId\":\"" + elevatedId + "\"}",
                "application/json");
        assertThat(rejected.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));

        // ... and an existing key stops working if the bound user is elevated afterwards.
        Application.getInstance(services.TenantDatabaseResolver.class)
                .tenantDatabase(tenant.databaseName())
                .getCollection(constants.CollectionName.tenantData("users"))
                .updateOne(new Document("id", boundId),
                        new Document("$set", new Document("role", enums.Role.SUPERADMIN)));

        TestResponse elevated = TestRequest.get("/api/auth/me")
                .withHeader("Authorization", "Bearer " + key)
                .execute();
        assertThat(elevated.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
    }

    @Test
    void plaintextIsReturnedOnceAndNeverStored() {
        UserService userService = Application.getInstance(UserService.class);
        String boundId = userId(userService.createUser("apikey-once-user", null, "secret-password-123"));
        CreatedKey created = createKey("apikey-once-key", boundId);

        TestResponse list = AdminTestUtils.getWithAdminCookies(
                "/api/meta/tenants/" + TenantTestUtils.defaultTenant().id() + "/api-keys",
                adminCookies());

        assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(list.getContent(), containsString("apikey-once-key"));
        assertThat(list.getContent(), not(containsString(created.key())));
        assertThat(list.getContent(), not(containsString("keyHash")));

        Document stored = Application.getInstance(services.TenantDatabaseResolver.class)
                .systemCollection(models.ApiKeyDefinition.COLLECTION)
                .find(new Document("id", created.id()))
                .first();

        assertThat(stored, notNullValue());
        assertThat(stored.toJson(), not(containsString(created.key())));
        assertThat(stored.getString("keyHash"), not(equalTo(created.key())));
        assertThat(stored.getString("keyHash"), equalTo(utils.ApiKeys.hash(created.key())));
    }

    @Test
    void issueTokenWorksWithKeyOnlyForAnAllowlistedUser() {
        UserService userService = Application.getInstance(UserService.class);
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        String issuerId = userId(userService.createUser("apikey-issuer", null, "secret-password-123"));
        String targetId = userId(userService.createUser("apikey-issue-target", null, "secret-password-123"));
        String key = createKey("apikey-issuer-key", issuerId).key();

        TenantService tenantService = Application.getInstance(TenantService.class);
        try {
            TestResponse denied = TestRequest.post("/api/auth/issue-token")
                    .withHeader("Authorization", "Bearer " + key)
                    .withStringBody("{\"userId\":\"" + targetId + "\"}")
                    .withContentType("application/json")
                    .execute();
            assertThat(denied.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));

            tenantService.update(tenant.id(), null, null, null, null, null, null, null, null, null,
                    null, List.of(issuerId));

            TestResponse allowed = TestRequest.post("/api/auth/issue-token")
                    .withHeader("Authorization", "Bearer " + key)
                    .withStringBody("{\"userId\":\"" + targetId + "\"}")
                    .withContentType("application/json")
                    .execute();

            assertThat(allowed.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(allowed.getContent(), containsString("\"accessToken\""));
        } finally {
            tenantService.update(tenant.id(), null, null, null, null, null, null, null, null, null,
                    null, List.of());
        }
    }

    @Test
    void passwordLoginAndRefreshKeepWorking() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("apikey-regression-user", null, "secret-password-123");

        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("apikey-regression-user", "secret-password-123"))
                .withContentType("application/json")
                .execute();

        assertThat(login.getStatusCode(), equalTo(StatusCodes.OK));
        String refreshToken = extractJsonString(login.getContent(), "refreshToken");

        TestResponse refresh = TestRequest.post("/api/auth/refresh")
                .withStringBody("{\"refreshToken\":\"" + refreshToken + "\"}")
                .withContentType("application/json")
                .execute();

        assertThat(refresh.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(refresh.getContent(), containsString("\"accessToken\""));
    }

    private record CreatedKey(String id, String key) {}

    private static CreatedKey createKey(String name, String userId) {
        return createKey(TenantTestUtils.defaultTenant(), name, userId, null);
    }

    private static CreatedKey createKey(String name, String userId, String expiresAt) {
        return createKey(TenantTestUtils.defaultTenant(), name, userId, expiresAt);
    }

    private static CreatedKey createKey(TenantDefinition tenant, String name, String userId, String expiresAt) {
        String body = expiresAt == null
                ? "{\"name\":\"" + name + "\",\"userId\":\"" + userId + "\"}"
                : "{\"name\":\"" + name + "\",\"userId\":\"" + userId + "\",\"expiresAt\":\"" + expiresAt + "\"}";

        TestResponse response = AdminTestUtils.postWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/api-keys",
                adminCookies(),
                body,
                "application/json");

        assertThat(response.getStatusCode(), equalTo(StatusCodes.CREATED));
        return new CreatedKey(
                extractJsonString(response.getContent(), "id"),
                extractJsonString(response.getContent(), "key"));
    }

    private static AdminTestUtils.AdminCookies adminCookies() {
        return new AdminTestUtils.AdminCookies(AdminTestUtils.loginAsAdmin(), null);
    }

    private static int listStatus(String collection, String key) {
        return TestRequest.get("/api/collections/" + collection + "?offset=0&limit=25")
                .withHeader("Authorization", "Bearer " + key)
                .execute()
                .getStatusCode();
    }

    private static CollectionRules authListRules() {
        return new CollectionRules("auth", "auth", null, null, null, null);
    }

    private static void seedOwnedCollection(String collection) {
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("owner", "owner", null, null, null, "owner"),
                List.of(new FieldDefinition("title", enums.FieldType.STRING, true, false, null)));
    }

    private static void seedOwnedRecord(String collection, String title, String ownerId) {
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        Application.getInstance(TenantCollectionService.class)
                .dataCollection(ctx, collection)
                .insertOne(new Document()
                        .append("id", DbUtils.id())
                        .append("title", title)
                        .append("owner", ownerId));
    }

    private static String accessTokenFor(String username) {
        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(username, "secret-password-123"))
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
