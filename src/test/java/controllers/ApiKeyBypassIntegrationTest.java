package controllers;

import auth.TenantContext;
import com.sun.net.httpserver.HttpServer;
import constants.SystemCollections;
import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionDefinition;
import models.CollectionRules;
import models.FieldDefinition;
import models.HookDefinition;
import models.HookEvent;
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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Covers rule-bypassing API keys: the credential a trusted backend service uses to work on a
 * tenant's data across user boundaries, which the four rule presets cannot express.
 * <p>
 * The two guarantees that make the bypass defensible are tested here as well: it does not reach
 * the management API, and it does not skip the hooks.
 */
@ExtendWith({TestRunner.class})
class ApiKeyBypassIntegrationTest {

    @Test
    void bypassKeyReadsALockedCollectionAnOrdinaryKeyCannot() {
        UserService userService = Application.getInstance(UserService.class);
        String boundId = userId(userService.createUser("bypass-read-user", null, "secret-password-123"));

        String collection = "posts_bypass_locked_read";
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());
        TenantTestUtils.seedRecord(collection, "locked record");

        String ordinaryKey = createKey("bypass-read-ordinary", boundId, false).key();
        String bypassKey = createKey("bypass-read-bypass", boundId, true).key();

        TestResponse withoutFlag = list(collection, ordinaryKey);
        assertThat(withoutFlag.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
        assertThat(withoutFlag.getContent(), not(containsString("locked record")));

        TestResponse withFlag = list(collection, bypassKey);
        assertThat(withFlag.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(withFlag.getContent(), containsString("locked record"));
    }

    @Test
    void bypassKeyWritesAndDeletesOnALockedCollection() {
        UserService userService = Application.getInstance(UserService.class);
        String boundId = userId(userService.createUser("bypass-write-user", null, "secret-password-123"));
        String key = createKey("bypass-write-key", boundId, true).key();

        String collection = "posts_bypass_locked_write";
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());

        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withHeader("Authorization", "Bearer " + key)
                .withStringBody("{\"title\":\"written by service\"}")
                .withContentType("application/json")
                .execute();
        assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));

        // A create answers 201 without a body, so the id comes from reading the collection back -
        // which a locked collection only allows for the bypass key in the first place.
        TestResponse afterCreate = list(collection, key);
        assertThat(afterCreate.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(afterCreate.getContent(), containsString("written by service"));
        String recordId = extractJsonString(afterCreate.getContent(), "id");

        TestResponse update = TestRequest.patch("/api/collections/" + collection + "/" + recordId)
                .withHeader("Authorization", "Bearer " + key)
                .withStringBody("{\"title\":\"changed by service\"}")
                .withContentType("application/json")
                .execute();
        assertThat(update.getStatusCode(), equalTo(StatusCodes.OK));

        TestResponse afterUpdate = TestRequest.get("/api/collections/" + collection + "/" + recordId)
                .withHeader("Authorization", "Bearer " + key)
                .execute();
        assertThat(afterUpdate.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(afterUpdate.getContent(), containsString("changed by service"));

        TestResponse delete = TestRequest.delete("/api/collections/" + collection + "/" + recordId)
                .withHeader("Authorization", "Bearer " + key)
                .execute();
        assertThat(delete.getStatusCode(), equalTo(StatusCodes.OK));

        TestResponse gone = TestRequest.get("/api/collections/" + collection + "/" + recordId)
                .withHeader("Authorization", "Bearer " + key)
                .execute();
        assertThat(gone.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
    }

    @Test
    void bypassKeySeesEveryRecordWhileAnOrdinaryKeySeesOnlyItsOwn() {
        UserService userService = Application.getInstance(UserService.class);
        String boundId = userId(userService.createUser("bypass-owner-user", null, "secret-password-123"));
        String strangerId = userId(userService.createUser("bypass-owner-stranger", null, "secret-password-123"));

        String collection = "posts_bypass_owner";
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("owner", "owner", null, null, null, "owner"),
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null)));
        seedOwnedRecord(collection, "owned by service user", boundId);
        seedOwnedRecord(collection, "owned by someone else", strangerId);

        TestResponse ordinary = list(collection, createKey("bypass-owner-ordinary", boundId, false).key());
        assertThat(ordinary.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(ordinary.getContent(), containsString("owned by service user"));
        assertThat(ordinary.getContent(), not(containsString("owned by someone else")));
        assertThat(ordinary.getContent(), containsString("\"total\":1"));

        TestResponse bypass = list(collection, createKey("bypass-owner-bypass", boundId, true).key());
        assertThat(bypass.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(bypass.getContent(), containsString("owned by service user"));
        assertThat(bypass.getContent(), containsString("owned by someone else"));
        assertThat(bypass.getContent(), containsString("\"total\":2"));
    }

    @Test
    void keyCreatedWithoutTheFlagIsAnOrdinaryKey() {
        UserService userService = Application.getInstance(UserService.class);
        String boundId = userId(userService.createUser("bypass-default-user", null, "secret-password-123"));
        TenantDefinition tenant = TenantTestUtils.defaultTenant();

        // Body without the field at all: the previous shape of this request must keep its meaning
        TestResponse created = AdminTestUtils.postWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/api-keys",
                adminCookies(),
                "{\"name\":\"bypass-default-key\",\"userId\":\"" + boundId + "\"}",
                "application/json");
        assertThat(created.getStatusCode(), equalTo(StatusCodes.CREATED));
        assertThat(created.getContent(), containsString("\"bypassRules\":false"));

        String key = extractJsonString(created.getContent(), "key");
        String collection = "posts_bypass_default";
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());
        TenantTestUtils.seedRecord(collection, "still locked");

        TestResponse response = list(collection, key);
        assertThat(response.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
        assertThat(response.getContent(), not(containsString("still locked")));
    }

    @Test
    void bypassKeyNeverReachesTheManagementApi() {
        UserService userService = Application.getInstance(UserService.class);
        String boundId = userId(userService.createUser("bypass-meta-user", null, "secret-password-123"));
        String key = createKey("bypass-meta-key", boundId, true).key();
        TenantDefinition tenant = TenantTestUtils.defaultTenant();

        for (String path : List.of(
                "/api/meta/collections/users",
                "/api/meta/tenants",
                "/api/meta/tenants/" + tenant.id(),
                "/api/meta/tenants/" + tenant.id() + "/api-keys",
                "/api/meta/global-hooks",
                "/api/meta/schema/export",
                "/api/admin/settings",
                "/api/admin/superadmins",
                "/api/admin/request-logs",
                "/api/admin/backup/export")) {

            TestResponse response = TestRequest.get(path)
                    .withHeader("Authorization", "Bearer " + key)
                    .execute();

            assertThat(path + " must stay closed for a bypass key",
                    response.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
        }

        // The writing counterparts matter more than the reading ones: schema import reshapes a
        // tenant, backup import replaces the whole instance. They are listed explicitly because a
        // GET-only loop would not notice a route that forgot its filter on the POST side.
        for (String path : List.of(
                "/api/meta/schema/import",
                "/api/admin/backup/import")) {

            TestResponse response = TestRequest.post(path)
                    .withHeader("Authorization", "Bearer " + key)
                    .withStringBody("{\"collections\":[]}")
                    .withContentType("application/json")
                    .execute();

            assertThat(path + " must stay closed for a bypass key",
                    response.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
        }
    }

    /**
     * The scheme of an {@code Authorization} header is case-insensitive, so spelling it in lower
     * case must not make a key look like an absent credential - which on the admin API would mean
     * falling through to the cookie branch instead of being rejected outright.
     */
    @Test
    void lowercaseBearerSchemeIsTreatedAsAKeyAsWell() {
        UserService userService = Application.getInstance(UserService.class);
        String boundId = userId(userService.createUser("bypass-lowercase-user", null, "secret-password-123"));
        String key = createKey("bypass-lowercase-key", boundId, true).key();

        String collection = "posts_bypass_lowercase";
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());
        TenantTestUtils.seedRecord(collection, "lowercase scheme record");

        TestResponse data = TestRequest.get("/api/collections/" + collection)
                .withHeader("Authorization", "bearer " + key)
                .execute();
        assertThat(data.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(data.getContent(), containsString("lowercase scheme record"));

        TestResponse meta = TestRequest.get("/api/meta/schema/export")
                .withHeader("Authorization", "bearer " + key)
                .execute();
        assertThat(meta.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
    }

    @Test
    void bypassKeyCannotReachAnotherTenant() {
        UserService userService = Application.getInstance(UserService.class);
        String boundId = userId(userService.createUser("bypass-tenant-user", null, "secret-password-123"));
        String key = createKey("bypass-tenant-key", boundId, true).key();

        TenantService tenantService = Application.getInstance(TenantService.class);
        TenantDefinition other = tenantService.create("Bypass Other", "bypass-other-tenant");
        String foreignCollection = "posts_bypass_foreign";

        try {
            TenantContext foreignCtx = TenantContext.guest(other.id(), other.databaseName());
            TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
            collections.insertDefinition(foreignCtx, new CollectionDefinition(
                    DbUtils.id(),
                    foreignCollection,
                    List.of(new FieldDefinition("title", FieldType.STRING, true, false, null)),
                    List.of(),
                    new CollectionRules("*", "*", "*", "*", "*", null),
                    false));
            collections.dataCollection(foreignCtx, foreignCollection)
                    .insertOne(new Document().append("id", DbUtils.id()).append("title", "foreign secret"));

            // Unknown collection names stay a 404 even with a bypass - the key is scoped to its
            // own tenant, where this collection simply does not exist.
            TestResponse response = list(foreignCollection, key);
            assertThat(response.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
            assertThat(response.getContent(), not(containsString("foreign secret")));
        } finally {
            tenantService.deleteWithCascade(other.id());
        }
    }

    @Test
    void hooksStillRunForABypassKey() throws IOException, InterruptedException {
        UserService userService = Application.getInstance(UserService.class);
        String boundId = userId(userService.createUser("bypass-hook-user", null, "secret-password-123"));
        String key = createKey("bypass-hook-key", boundId, true).key();

        String collection = "posts_bypass_hook";
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());

        BlockingQueue<String> payloads = new ArrayBlockingQueue<>(8);
        HttpServer server = startHookServer(payloads);
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        TenantService tenantService = Application.getInstance(TenantService.class);
        tenantService.update(tenant.id(), null, null, null, null, null, null, null, null, null,
                List.of("127.0.0.1:" + server.getAddress().getPort()));

        HookDefinition hook = blockingBeforeCreateHook(server, collection);
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        collections.insertHook(ctx, hook);

        try {
            TestResponse create = TestRequest.post("/api/collections/" + collection)
                    .withHeader("Authorization", "Bearer " + key)
                    .withStringBody("{\"title\":\"should be blocked\"}")
                    .withContentType("application/json")
                    .execute();

            // A blocking hook stops a bypass key just like any other caller: the rules are
            // skipped, the business logic in the hooks is not.
            assertThat(create.getStatusCode(), equalTo(422));
            assertThat(create.getContent(), containsString("Blocked by hook"));

            String payload = payloads.poll(10, TimeUnit.SECONDS);
            assertThat(payload, notNullValue());
            // The envelope describes the bound tenant user, not an admin
            assertThat(payload, containsString("\"id\":\"" + boundId + "\""));
            assertThat(payload, containsString("\"role\":\"user\""));
        } finally {
            collections.deleteHook(ctx, hook.id());
            server.stop(0);
            tenantService.update(tenant.id(), null, null, null, null, null, null, null, null, null, List.of());
        }
    }

    @Test
    void bypassFlagIsRejectedForASuperadminBoundUserAndCannotBeChangedLater() {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        UserService userService = Application.getInstance(UserService.class);
        String boundId = userId(userService.createUser("bypass-immutable-user", null, "secret-password-123"));

        String elevatedId = DbUtils.id();
        Application.getInstance(services.TenantDatabaseResolver.class)
                .tenantDatabase(tenant.databaseName())
                .getCollection(constants.CollectionName.tenantData(SystemCollections.USERS))
                .insertOne(new Document()
                        .append("id", elevatedId)
                        .append("username", "bypass-elevated-user")
                        .append("role", enums.Role.SUPERADMIN));

        TestResponse rejected = AdminTestUtils.postWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/api-keys",
                adminCookies(),
                "{\"name\":\"bypass-elevated\",\"userId\":\"" + elevatedId + "\",\"bypassRules\":true}",
                "application/json");
        assertThat(rejected.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));

        // There is no route that could flip the flag of an existing key
        CreatedKey ordinary = createKey("bypass-immutable-key", boundId, false);
        String path = "/api/meta/tenants/" + tenant.id() + "/api-keys/" + ordinary.id();
        TestResponse patched = AdminTestUtils.patchWithAdminCookies(
                path, adminCookies(), "{\"bypassRules\":true}", "application/json");
        assertThat(patched.getStatusCode(), anyOf(
                equalTo(StatusCodes.NOT_FOUND),
                equalTo(StatusCodes.METHOD_NOT_ALLOWED)));

        TestResponse list = AdminTestUtils.getWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/api-keys", adminCookies());
        assertThat(list.getContent(), containsString("\"name\":\"bypass-immutable-key\""));
        assertThat(list.getContent(), not(containsString("\"name\":\"bypass-elevated\"")));
    }

    @Test
    void listExposesTheClassificationForTheAdminUi() {
        UserService userService = Application.getInstance(UserService.class);
        String boundId = userId(userService.createUser("bypass-list-user", null, "secret-password-123"));
        createKey("bypass-list-privileged", boundId, true);

        TestResponse list = AdminTestUtils.getWithAdminCookies(
                "/api/meta/tenants/" + TenantTestUtils.defaultTenant().id() + "/api-keys",
                adminCookies());

        assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(list.getContent(), containsString("\"bypassRules\":true"));
        assertThat(list.getContent(), containsString("\"bypassRules\":false"));
    }

    private record CreatedKey(String id, String key) {}

    private static CreatedKey createKey(String name, String userId, boolean bypassRules) {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        TestResponse response = AdminTestUtils.postWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/api-keys",
                adminCookies(),
                "{\"name\":\"" + name + "\",\"userId\":\"" + userId + "\",\"bypassRules\":" + bypassRules + "}",
                "application/json");

        assertThat(response.getStatusCode(), equalTo(StatusCodes.CREATED));
        assertThat(response.getContent(), containsString("\"bypassRules\":" + bypassRules));
        return new CreatedKey(
                extractJsonString(response.getContent(), "id"),
                extractJsonString(response.getContent(), "key"));
    }

    private static AdminTestUtils.AdminCookies adminCookies() {
        return new AdminTestUtils.AdminCookies(AdminTestUtils.loginAsAdmin(), null);
    }

    private static TestResponse list(String collection, String key) {
        return TestRequest.get("/api/collections/" + collection + "?offset=0&limit=25")
                .withHeader("Authorization", "Bearer " + key)
                .execute();
    }

    private static void seedOwnedRecord(String collection, String title, String ownerId) {
        Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), collection)
                .insertOne(new Document()
                        .append("id", DbUtils.id())
                        .append("title", title)
                        .append("owner", ownerId));
    }

    private static HookDefinition blockingBeforeCreateHook(HttpServer server, String collection) {
        return new HookDefinition(
                DbUtils.id(),
                "bypass-before-create-hook",
                null,
                collection,
                HookEvent.beforeCreate,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/hook",
                null,
                null,
                "test-secret",
                null,
                true,
                null,
                null,
                false,
                null,
                null,
                null,
                null);
    }

    private static HttpServer startHookServer(BlockingQueue<String> payloads) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            byte[] request = exchange.getRequestBody().readAllBytes();
            payloads.offer(new String(request, StandardCharsets.UTF_8));
            byte[] body = "{\"continue\": false, \"error\": {\"status\": 422, \"message\": \"Blocked by hook\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return server;
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
