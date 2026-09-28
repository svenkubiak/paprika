package controllers;

import auth.TenantContext;
import com.mongodb.client.model.Sorts;
import com.sun.net.httpserver.HttpServer;
import constants.GlobalHooks;
import constants.SystemCollections;
import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionRules;
import models.FieldDefinition;
import models.FieldOptions;
import models.FileReference;
import models.HookDefinition;
import models.HookEvent;
import models.TenantDefinition;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import services.TenantDatabaseResolver;
import services.TenantService;
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
import java.util.concurrent.atomic.AtomicInteger;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Covers hook-free API keys: the exemption for the one service a hook itself calls.
 * <p>
 * A global beforeRequest hook used as an external authorizer asks a service on every request. When
 * that service asks Paprika back with its own key, each of its lookups re-enters the hook it came
 * from - a roundtrip whose outcome is fixed before it starts, and which under load eats the very
 * time budget the outer request is waiting on. {@code bypassHooks} takes that key out of the loop.
 * <p>
 * What the tests hold on to: the exemption reaches all three routes that run hooks, it is a
 * property of one key rather than of API keys in general, it cannot be acquired after the fact,
 * and the request log says out loud that it applied - otherwise a missing hook entry is
 * indistinguishable from a hook that failed to run.
 */
@ExtendWith({TestRunner.class})
class ApiKeyHookBypassIntegrationTest {
    private static final String BLOCKING_RESPONSE =
            "{\"continue\": false, \"error\": {\"status\": 403, \"message\": \"device not trusted\"}}";
    private static final String FILE_CONTENT = "hook-free-file-content";

    @Test
    void aHookFreeKeyRunsNoGlobalBeforeRequestHookOnACollectionRoute() throws IOException {
        String boundId = user("hookfree-global");
        String guarded = createKey("hookfree-global-guarded", boundId, false).key();
        String exempt = createKey("hookfree-global-exempt", boundId, true).key();

        String collection = "posts_hookfree_global";
        TenantTestUtils.seedCollection(collection, publicRules());
        TenantTestUtils.seedRecord(collection, "guarded record");

        AtomicInteger calls = new AtomicInteger();
        HttpServer server = startHookServer(calls, BLOCKING_RESPONSE);
        allowWebhookHost(server.getAddress().getPort());
        HookDefinition hook = globalBeforeRequestHook(hookUrl(server), false);
        insertHook(hook);

        try {
            TestResponse guardedList = list(collection, guarded);
            assertThat("the hook decides over an ordinary key",
                    guardedList.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
            assertThat(guardedList.getContent(), containsString("device not trusted"));
            assertThat(calls.get(), equalTo(1));

            TestResponse exemptList = list(collection, exempt);
            assertThat(exemptList.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(exemptList.getContent(), containsString("guarded record"));
            assertThat("the hook must not be called at all for a hook-free key",
                    calls.get(), equalTo(1));
        } finally {
            cleanup(hook, server);
        }
    }

    @Test
    void aHookFreeKeyRunsNoCollectionLifecycleHook() throws IOException {
        String boundId = user("hookfree-lifecycle");
        String guarded = createKey("hookfree-lifecycle-guarded", boundId, false).key();
        String exempt = createKey("hookfree-lifecycle-exempt", boundId, true).key();

        String collection = "posts_hookfree_lifecycle";
        TenantTestUtils.seedCollection(collection, publicRules());

        AtomicInteger calls = new AtomicInteger();
        HttpServer server = startHookServer(calls, BLOCKING_RESPONSE);
        allowWebhookHost(server.getAddress().getPort());
        HookDefinition hook = beforeCreateHook(hookUrl(server), collection);
        insertHook(hook);

        try {
            TestResponse blocked = create(collection, guarded, "blocked by the hook");
            assertThat(blocked.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
            assertThat(calls.get(), equalTo(1));

            TestResponse created = create(collection, exempt, "written without a hook");
            assertThat(created.getStatusCode(), equalTo(StatusCodes.CREATED));
            assertThat(calls.get(), equalTo(1));
        } finally {
            cleanup(hook, server);
        }
    }

    /**
     * The file routes carry their own hook filter, and a download that skipped the authorizer on
     * one route but not the other would be the worse surprise of the two.
     */
    @Test
    void aHookFreeKeyRunsNoHookOnAFileRoute() throws IOException {
        String boundId = user("hookfree-file");
        String guarded = createKey("hookfree-file-guarded", boundId, false).key();
        String exempt = createKey("hookfree-file-exempt", boundId, true).key();

        String collection = "docs_hookfree_file";
        seedFileCollection(collection);
        Uploaded uploaded = upload(collection, guarded);

        AtomicInteger calls = new AtomicInteger();
        HttpServer server = startHookServer(calls, BLOCKING_RESPONSE);
        allowWebhookHost(server.getAddress().getPort());
        HookDefinition hook = globalBeforeRequestHook(hookUrl(server), true);
        insertHook(hook);

        try {
            String path = "/api/collections/" + collection + "/" + uploaded.recordId()
                    + "/files/attachment/" + uploaded.fileId();

            TestResponse blocked = TestRequest.get(path)
                    .withHeader("Authorization", "Bearer " + guarded)
                    .execute();
            assertThat(blocked.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
            assertThat(blocked.getContent(), not(containsString(FILE_CONTENT)));
            assertThat(calls.get(), equalTo(1));

            TestResponse download = TestRequest.get(path)
                    .withHeader("Authorization", "Bearer " + exempt)
                    .execute();
            assertThat(download.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(download.getContent(), containsString(FILE_CONTENT));
            assertThat(calls.get(), equalTo(1));
        } finally {
            cleanup(hook, server);
        }
    }

    /** The auth routes run the global hooks through a filter of their own. */
    @Test
    void aHookFreeKeyRunsNoHookOnAnAuthRoute() throws IOException {
        String boundId = user("hookfree-auth");
        String guarded = createKey("hookfree-auth-guarded", boundId, false).key();
        String exempt = createKey("hookfree-auth-exempt", boundId, true).key();

        AtomicInteger calls = new AtomicInteger();
        HttpServer server = startHookServer(calls, BLOCKING_RESPONSE);
        allowWebhookHost(server.getAddress().getPort());
        HookDefinition hook = globalBeforeRequestHook(hookUrl(server), false);
        insertHook(hook);

        try {
            TestResponse blocked = TestRequest.get("/api/auth/me")
                    .withHeader("Authorization", "Bearer " + guarded)
                    .execute();
            assertThat(blocked.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
            assertThat(calls.get(), equalTo(1));

            TestResponse me = TestRequest.get("/api/auth/me")
                    .withHeader("Authorization", "Bearer " + exempt)
                    .execute();
            assertThat(me.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(me.getContent(), containsString(boundId));
            assertThat(calls.get(), equalTo(1));
        } finally {
            cleanup(hook, server);
        }
    }

    /**
     * Without this marker the entry of a hook-free request looks exactly like one whose hook
     * silently did not run, and telling the two apart is what the log is for during an incident.
     */
    @Test
    void theRequestLogMarksAHookFreeRequest() {
        String boundId = user("hookfree-log");
        String exempt = createKey("hookfree-log-exempt", boundId, true).key();
        String ordinary = createKey("hookfree-log-ordinary", boundId, false).key();

        String collection = "posts_hookfree_log";
        TenantTestUtils.seedCollection(collection, publicRules());

        String url = "/api/collections/" + collection;
        assertThat(list(collection, exempt).getStatusCode(), equalTo(StatusCodes.OK));
        Document exemptEntry = awaitEntry(url, "hookfree-log-exempt");
        assertThat(exemptEntry.getBoolean("hooksBypassed"), equalTo(true));

        assertThat(list(collection, ordinary).getStatusCode(), equalTo(StatusCodes.OK));
        Document ordinaryEntry = awaitEntry(url, "hookfree-log-ordinary");
        assertThat("an ordinary key must not be marked as exempt",
                ordinaryEntry.getBoolean("hooksBypassed"), nullValue());
    }

    /** The two exemptions are independent: neither implies the other. */
    @Test
    void theTwoBypassFlagsAreIndependent() throws IOException {
        String boundId = user("hookfree-independent");
        String rulesOnly = createKeyWith("hookfree-rules-only", boundId, true, false).key();
        String hooksOnly = createKeyWith("hookfree-hooks-only", boundId, false, true).key();

        String collection = "posts_hookfree_independent";
        TenantTestUtils.seedCollection(collection, CollectionRules.locked());
        TenantTestUtils.seedRecord(collection, "locked record");

        AtomicInteger calls = new AtomicInteger();
        HttpServer server = startHookServer(calls, BLOCKING_RESPONSE);
        allowWebhookHost(server.getAddress().getPort());
        HookDefinition hook = globalBeforeRequestHook(hookUrl(server), false);
        insertHook(hook);

        try {
            // Rules bypassed, hooks not: the hook still decides, and it says no
            TestResponse ruleBypassing = list(collection, rulesOnly);
            assertThat(ruleBypassing.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
            assertThat(ruleBypassing.getContent(), containsString("device not trusted"));
            assertThat(calls.get(), equalTo(1));

            // Hooks bypassed, rules not: no hook runs, and the locked collection still holds
            TestResponse hookFree = list(collection, hooksOnly);
            assertThat(hookFree.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
            assertThat(hookFree.getContent(), not(containsString("locked record")));
            assertThat(hookFree.getContent(), not(containsString("device not trusted")));
            assertThat(calls.get(), equalTo(1));
        } finally {
            cleanup(hook, server);
        }
    }

    @Test
    void theFlagIsVisibleInTheListAndCannotBeSetAfterwards() {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        String boundId = user("hookfree-immutable");

        // Both classifications are created here on purpose: the assertions below would otherwise
        // be satisfied by a key some other test left in the tenant, which makes this test pass or
        // fail depending on the order Surefire happens to pick.
        createKey("hookfree-list-exempt", boundId, true);
        CreatedKey ordinary = createKey("hookfree-list-ordinary", boundId, false);

        TestResponse list = AdminTestUtils.getWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/api-keys", adminCookies());
        assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(list.getContent(), containsString("\"bypassHooks\":true"));
        assertThat(list.getContent(), containsString("\"bypassHooks\":false"));

        // There is no route that could flip the flag of an existing key
        TestResponse patched = AdminTestUtils.patchWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/api-keys/" + ordinary.id(),
                adminCookies(),
                "{\"bypassHooks\":true}",
                "application/json");
        assertThat(patched.getStatusCode(), anyOf(
                equalTo(StatusCodes.NOT_FOUND),
                equalTo(StatusCodes.METHOD_NOT_ALLOWED)));
    }

    private record CreatedKey(String id, String key) {}

    private record Uploaded(String recordId, String fileId) {}

    private static String user(String prefix) {
        UserService userService = Application.getInstance(UserService.class);
        Map<String, Object> user = userService.createUser(
                prefix + "-" + DbUtils.id(), null, "secret-password-123");
        return String.valueOf(user.get("id"));
    }

    /** Public on every operation, so only the hook can be the reason a request is refused. */
    private static CollectionRules publicRules() {
        return new CollectionRules("*", "*", "*", "*", "*", "owner");
    }

    private static CreatedKey createKey(String name, String userId, boolean bypassHooks) {
        return createKeyWith(name, userId, false, bypassHooks);
    }

    private static CreatedKey createKeyWith(
            String name, String userId, boolean bypassRules, boolean bypassHooks) {

        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        TestResponse response = AdminTestUtils.postWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/api-keys",
                adminCookies(),
                "{\"name\":\"" + name + "\",\"userId\":\"" + userId + "\""
                        + ",\"bypassRules\":" + bypassRules
                        + ",\"bypassHooks\":" + bypassHooks + "}",
                "application/json");

        assertThat(response.getStatusCode(), equalTo(StatusCodes.CREATED));
        assertThat(response.getContent(), containsString("\"bypassHooks\":" + bypassHooks));
        return new CreatedKey(
                extractJsonString(response.getContent(), "id"),
                extractJsonString(response.getContent(), "key"));
    }

    private static AdminTestUtils.AdminCookies adminCookies() {
        return new AdminTestUtils.AdminCookies(AdminTestUtils.loginAsAdmin(), null);
    }

    private static TestResponse list(String collection, String key) {
        return get("/api/collections/" + collection + "?offset=0&limit=25", key);
    }

    private static TestResponse get(String url, String key) {
        return TestRequest.get(url).withHeader("Authorization", "Bearer " + key).execute();
    }

    private static TestResponse create(String collection, String key, String title) {
        return TestRequest.post("/api/collections/" + collection)
                .withHeader("Authorization", "Bearer " + key)
                .withStringBody("{\"title\":\"" + title + "\"}")
                .withContentType("application/json")
                .execute();
    }

    private static void seedFileCollection(String collection) {
        TenantTestUtils.seedCollection(
                collection,
                publicRules(),
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("attachment", FieldType.FILE, true, false,
                                FieldOptions.forFile(1024 * 1024, List.of("text/plain"), 2))));
    }

    private static Uploaded upload(String collection, String key) {
        String boundary = "----paprika-test";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
                + "hello\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"attachment\"; filename=\"note.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + FILE_CONTENT + "\r\n"
                + "--" + boundary + "--\r\n";

        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withHeader("Authorization", "Bearer " + key)
                .withHeader("Content-Type", "multipart/form-data; boundary=" + boundary)
                .withStringBody(body)
                .execute();
        assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));

        Document record = Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), collection)
                .find()
                .first();
        assertThat(record, notNullValue());

        List<FileReference> references = FileReference.listFromValue(record.get("attachment"));
        assertThat(references, hasSize(1));
        return new Uploaded(record.getString("id"), references.getFirst().id());
    }

    private static HookDefinition globalBeforeRequestHook(String url, boolean includeFileRoutes) {
        return new HookDefinition(
                DbUtils.id(),
                "hookfree-gate-" + DbUtils.id(),
                null,
                GlobalHooks.COLLECTION,
                HookEvent.beforeRequest,
                url,
                null,
                1000,
                "test-secret",
                null,
                true,
                null,
                null,
                false,
                true,
                null,
                null,
                includeFileRoutes);
    }

    private static HookDefinition beforeCreateHook(String url, String collection) {
        return new HookDefinition(
                DbUtils.id(),
                "hookfree-before-create-" + DbUtils.id(),
                null,
                collection,
                HookEvent.beforeCreate,
                url,
                null,
                1000,
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

    private static void insertHook(HookDefinition hook) {
        Application.getInstance(TenantCollectionService.class)
                .insertHook(TenantTestUtils.defaultTenantContext(), hook);
    }

    private static void cleanup(HookDefinition hook, HttpServer server) {
        Application.getInstance(TenantCollectionService.class)
                .deleteHook(TenantTestUtils.defaultTenantContext(), hook.id());
        if (server != null) {
            server.stop(0);
        }
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        Application.getInstance(TenantService.class).update(
                tenant.id(), null, null, null, null, null, null, null, null, null, List.of());
    }

    private static void allowWebhookHost(int port) {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        Application.getInstance(TenantService.class).update(
                tenant.id(), null, null, null, null, null, null, null, null, null,
                List.of("127.0.0.1:" + port));
    }

    private static HttpServer startHookServer(AtomicInteger calls, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static String hookUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    /** Entries are written while the response is rendered, so a short poll avoids flakiness. */
    private static Document awaitEntry(String url, String apiKeyName) {
        for (int attempt = 0; attempt < 100; attempt++) {
            TenantContext ctx = TenantTestUtils.defaultTenantContext();
            Document entry = Application.getInstance(TenantDatabaseResolver.class)
                    .tenantMetaCollection(ctx, SystemCollections.REQUEST_LOGS)
                    .find(and(eq("url", url), eq("apiKeyName", apiKeyName)))
                    .sort(Sorts.descending("timestamp"))
                    .first();
            if (entry != null) {
                return entry;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("No request log entry for " + url);
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
