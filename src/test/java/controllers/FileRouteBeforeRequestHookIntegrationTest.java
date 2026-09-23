package controllers;

import auth.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import constants.GlobalHooks;
import constants.SystemCollections;
import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.mangoo.utils.JsonUtils;
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
import services.FileStorageService;
import services.TenantCollectionService;
import services.TenantDatabaseResolver;
import services.TenantService;
import services.UserService;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.mongodb.client.model.Filters.eq;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * The file routes (/api/collections/{collection}/{id}/files/{field}[/{fileId}]) were the one data
 * plane a global beforeRequest hook never saw, so a hook acting as an external authorizer did not
 * decide over downloads and file deletions. It does now - but only when the hook opted in via
 * includeFileRoutes, because an existing hook was written for a different set of routes and a
 * guard that rejects the unknown would otherwise block every download after an upgrade.
 */
@ExtendWith({TestRunner.class})
class FileRouteBeforeRequestHookIntegrationTest {
    private static final String FILE_CONTENT = "secret-file-content";

    @Test
    void anAllowingHookLetsTheDownloadThroughAndIsInvokedExactlyOnce() throws IOException {
        Fixture fixture = fixture();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> payload = new AtomicReference<>();
        HttpServer server = startHookServer(calls, payload, "{\"continue\": true}", 200);
        allowWebhookHost(server.getAddress().getPort());
        HookDefinition hook = globalHook(hookUrl(server), true, true, null, false, null);
        insertHook(hook);

        try {
            TestResponse download = download(fixture);

            assertThat(download.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(download.getContent(), containsString(FILE_CONTENT));
            assertThat("a file request must not fan out into several hook deliveries",
                    calls.get(), equalTo(1));

            JsonNode envelope = JsonUtils.getMapper().readTree(payload.get());
            assertThat(envelope.get("paprika").get("event").asText(), equalTo("beforeRequest"));
            assertThat(envelope.get("context").get("collection").asText(), equalTo(fixture.collection()));
            assertThat(envelope.get("context").get("recordId").asText(), equalTo(fixture.recordId()));
            assertThat(envelope.get("context").get("http").get("method").asText(), equalTo("GET"));
            // The hook filter runs after ApiAuthFilter, so a guard can recognize the caller.
            assertThat(envelope.get("context").get("auth").get("id").asText(),
                    equalTo(record(fixture.collection(), fixture.recordId()).getString("owner")));
            assertThat(envelope.get("context").get("auth").get("role").asText(), not(emptyString()));
            assertThat(envelope.get("context").get("http").get("path").asText(),
                    equalTo(downloadUrl(fixture)));
            // beforeRequest is the upfront filter, not the lifecycle event: no record is loaded.
            assertThat(envelope.get("data").get("body").isNull(), equalTo(true));
            assertThat(envelope.get("data").get("record").isNull(), equalTo(true));

            Document entry = awaitEntry(downloadUrl(fixture));
            assertThat(entry.getInteger("hookCount"), equalTo(1));
            assertThat(entry.getBoolean("hookFired"), equalTo(true));
            List<Document> hooks = entry.getList("hooks", Document.class);
            assertThat(hooks, hasSize(1));
            assertThat(hooks.getFirst().getString("event"), equalTo("beforeRequest"));
            assertThat(hooks.getFirst().getString("outcome"), equalTo("continued"));
            assertThat(hooks.getFirst().get("durationMs"), notNullValue());
        } finally {
            cleanup(hook, server);
        }
    }

    @Test
    void aRejectingHookBlocksTheDownloadWithItsOwnStatusAndBody() throws IOException {
        Fixture fixture = fixture();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = startHookServer(
                calls,
                new AtomicReference<>(),
                "{\"continue\": false, \"error\": {\"status\": 403, \"message\": \"device not trusted\"}}",
                200);
        allowWebhookHost(server.getAddress().getPort());
        HookDefinition hook = globalHook(hookUrl(server), true, true, null, false, null);
        insertHook(hook);

        try {
            TestResponse download = download(fixture);

            assertThat(download.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
            assertThat(download.getContent(), containsString("device not trusted"));
            assertThat("the file must not leak in the rejection body",
                    download.getContent(), not(containsString(FILE_CONTENT)));
            assertThat(calls.get(), equalTo(1));
        } finally {
            cleanup(hook, server);
        }
    }

    /** The regression test for the opt-in: an existing hook keeps seeing no file traffic. */
    @Test
    void aHookWithoutTheFileRouteOptInIsNotInvoked() throws IOException {
        Fixture fixture = fixture();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = startHookServer(
                calls, new AtomicReference<>(), "{\"continue\": false, \"error\": {\"status\": 403}}", 200);
        allowWebhookHost(server.getAddress().getPort());
        HookDefinition hook = globalHook(hookUrl(server), true, null, null, false, null);
        insertHook(hook);

        try {
            TestResponse download = download(fixture);

            assertThat(download.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(download.getContent(), containsString(FILE_CONTENT));
            assertThat("a hook that did not opt in must not be called at all", calls.get(), equalTo(0));
        } finally {
            cleanup(hook, server);
        }
    }

    /** The collection scope applies here as on any other collection route. */
    @Test
    void aHookScopedToOtherCollectionsIsNotInvoked() throws IOException {
        Fixture fixture = fixture();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = startHookServer(
                calls, new AtomicReference<>(), "{\"continue\": false, \"error\": {\"status\": 403}}", 200);
        allowWebhookHost(server.getAddress().getPort());
        HookDefinition hook = globalHook(
                hookUrl(server), false, true, List.of("some_other_collection"), false, null);
        insertHook(hook);

        try {
            TestResponse download = download(fixture);

            assertThat(download.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(calls.get(), equalTo(0));
        } finally {
            cleanup(hook, server);
        }
    }

    @Test
    void aRejectedDeleteLeavesTheFileInPlace() throws IOException {
        Fixture fixture = fixture();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = startHookServer(
                calls, new AtomicReference<>(), "{\"continue\": false, \"error\": {\"status\": 403}}", 200);
        allowWebhookHost(server.getAddress().getPort());
        HookDefinition hook = globalHook(hookUrl(server), true, true, null, false, null);
        insertHook(hook);

        try {
            TestResponse delete = TestRequest.delete(downloadUrl(fixture))
                    .withHeader("Authorization", "Bearer " + fixture.token())
                    .execute();

            assertThat(delete.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
            assertThat(calls.get(), equalTo(1));

            // The status code alone would not prove anything: check the record and the storage.
            Document record = record(fixture.collection(), fixture.recordId());
            List<FileReference> references = FileReference.listFromValue(record.get("attachment"));
            assertThat(references.stream().map(FileReference::id).toList(), hasItem(fixture.fileId()));

            byte[] stored = Application.getInstance(FileStorageService.class)
                    .read(TenantTestUtils.defaultTenantContext(), fixture.fileId());
            assertThat(new String(stored, StandardCharsets.UTF_8), equalTo(FILE_CONTENT));
        } finally {
            cleanup(hook, server);
        }
    }

    @Test
    void anUnreachableHookPassesThroughWhenItMayFailOpen() throws IOException {
        Fixture fixture = fixture();
        int deadPort = closedPort();
        allowWebhookHost(deadPort);
        HookDefinition hook = globalHook(deadHookUrl(deadPort), true, true, null, true, null);
        insertHook(hook);

        try {
            TestResponse download = download(fixture);

            assertThat(download.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(download.getContent(), containsString(FILE_CONTENT));
        } finally {
            cleanup(hook, null);
        }
    }

    @Test
    void anUnreachableHookFailsTheRequestWhenItMustNot() throws IOException {
        Fixture fixture = fixture();
        int deadPort = closedPort();
        allowWebhookHost(deadPort);
        HookDefinition hook = globalHook(deadHookUrl(deadPort), true, true, null, false, null);
        insertHook(hook);

        try {
            TestResponse download = download(fixture);

            assertThat(download.getStatusCode(), equalTo(StatusCodes.BAD_GATEWAY));
            assertThat(download.getContent(), not(containsString(FILE_CONTENT)));
        } finally {
            cleanup(hook, null);
        }
    }

    @Test
    void forwardedHeadersReachTheHookOnAFileRouteAndCredentialsDoNot() throws IOException {
        Fixture fixture = fixture();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> payload = new AtomicReference<>();
        HttpServer server = startHookServer(calls, payload, "{\"continue\": true}", 200);
        allowWebhookHost(server.getAddress().getPort());
        HookDefinition hook = globalHook(
                hookUrl(server), true, true, null, false, List.of("x-app-key-id"));
        insertHook(hook);

        try {
            TestResponse download = TestRequest.get(downloadUrl(fixture))
                    .withHeader("Authorization", "Bearer " + fixture.token())
                    .withHeader("Cookie", "paprika-authentication=ADMIN_SESSION_JWT_VALUE")
                    .withHeader("X-App-Key-Id", "app-key-42")
                    .withHeader("X-Other-Header", "not-forwarded-value")
                    .execute();
            assertThat(download.getStatusCode(), equalTo(StatusCodes.OK));

            List<String> headers = headerNames(payload.get());
            assertThat(headers, hasItem("x-app-key-id"));
            assertThat(payload.get(), containsString("app-key-42"));
            assertThat(headers, not(hasItem("authorization")));
            assertThat(headers, not(hasItem("cookie")));
            assertThat(headers, not(hasItem("x-other-header")));
            assertThat(payload.get(), not(containsString(fixture.token())));
        } finally {
            cleanup(hook, server);
        }
    }

    /** The other two routes - download and delete without an explicit file id - are guarded too. */
    @Test
    void theSingleFileRoutesAreGuardedAsWell() throws IOException {
        Fixture fixture = fixture(1);
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = startHookServer(
                calls, new AtomicReference<>(), "{\"continue\": false, \"error\": {\"status\": 403}}", 200);
        allowWebhookHost(server.getAddress().getPort());
        HookDefinition hook = globalHook(hookUrl(server), true, true, null, false, null);
        insertHook(hook);

        try {
            TestResponse download = TestRequest.get(fieldPath(fixture))
                    .withHeader("Authorization", "Bearer " + fixture.token())
                    .execute();
            assertThat(download.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));

            TestResponse delete = TestRequest.delete(fieldPath(fixture))
                    .withHeader("Authorization", "Bearer " + fixture.token())
                    .execute();
            assertThat(delete.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));

            assertThat(calls.get(), equalTo(2));

            byte[] stored = Application.getInstance(FileStorageService.class)
                    .read(TenantTestUtils.defaultTenantContext(), fixture.fileId());
            assertThat(new String(stored, StandardCharsets.UTF_8), equalTo(FILE_CONTENT));
        } finally {
            cleanup(hook, server);
        }
    }

    /** A hook must not become an oracle for which collections exist. */
    @Test
    void anUnknownCollectionStays404AndFiresNoHook() throws IOException {
        Fixture fixture = fixture();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = startHookServer(calls, new AtomicReference<>(), "{\"continue\": true}", 200);
        allowWebhookHost(server.getAddress().getPort());
        HookDefinition hook = globalHook(hookUrl(server), true, true, null, false, null);
        insertHook(hook);

        try {
            TestResponse download = TestRequest
                    .get("/api/collections/does_not_exist_" + DbUtils.id() + "/"
                            + fixture.recordId() + "/files/attachment")
                    .withHeader("Authorization", "Bearer " + fixture.token())
                    .execute();

            assertThat(download.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
            assertThat(calls.get(), equalTo(0));
        } finally {
            cleanup(hook, server);
        }
    }

    private record Fixture(String collection, String recordId, String fileId, String token) {
    }

    private static Fixture fixture() {
        return fixture(2);
    }

    private static Fixture fixture(int maxSelect) {
        String username = "file-hook-user-" + DbUtils.id();
        Application.getInstance(UserService.class).createUser(username, null, "secret-password-123");
        String token = loginToken(username, "secret-password-123");

        String collection = "docs_filehook_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("owner", "owner", "auth", "owner", "owner", "owner"),
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("owner", FieldType.RELATION, false, true,
                                FieldOptions.forRelation("users")),
                        new FieldDefinition("attachment", FieldType.FILE, true, false,
                                FieldOptions.forFile(1024 * 1024, List.of("text/plain"), maxSelect))));

        String boundary = "----paprika-test";
        String multipartBody = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
                + "hello\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"attachment\"; filename=\"note.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + FILE_CONTENT + "\r\n"
                + "--" + boundary + "--\r\n";

        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withHeader("Authorization", "Bearer " + token)
                .withHeader("Content-Type", "multipart/form-data; boundary=" + boundary)
                .withStringBody(multipartBody)
                .execute();
        assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));

        Document record = Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), collection)
                .find()
                .first();
        assertThat(record, notNullValue());

        List<FileReference> references = FileReference.listFromValue(record.get("attachment"));
        assertThat(references, hasSize(1));

        return new Fixture(collection, record.getString("id"), references.getFirst().id(), token);
    }

    private static TestResponse download(Fixture fixture) {
        return TestRequest.get(downloadUrl(fixture))
                .withHeader("Authorization", "Bearer " + fixture.token())
                .execute();
    }

    /**
     * The file field holds more than one file, so a file request addresses the file explicitly -
     * which exercises the downloadWithId/deleteFileById routes.
     */
    private static String downloadUrl(Fixture fixture) {
        return fieldPath(fixture) + "/" + fixture.fileId();
    }

    private static String fieldPath(Fixture fixture) {
        return "/api/collections/" + fixture.collection() + "/" + fixture.recordId() + "/files/attachment";
    }

    private static Document record(String collection, String recordId) {
        Document record = Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), collection)
                .find(eq("id", recordId))
                .first();
        assertThat(record, notNullValue());
        return record;
    }

    private static HookDefinition globalHook(
            String url,
            boolean applyToAllCollections,
            Boolean includeFileRoutes,
            List<String> targetCollections,
            boolean failOpen,
            List<String> forwardHeaders) {

        return new HookDefinition(
                DbUtils.id(),
                "file-route-gate-" + DbUtils.id(),
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
                failOpen,
                applyToAllCollections,
                targetCollections,
                forwardHeaders,
                includeFileRoutes);
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

    private static HttpServer startHookServer(
            AtomicInteger calls,
            AtomicReference<String> payload,
            String body,
            int status) throws IOException {

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            calls.incrementAndGet();
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
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

    private static String deadHookUrl(int port) {
        return "http://127.0.0.1:" + port + "/hook";
    }

    /** A port that was bound once and released again: nothing answers there. */
    private static int closedPort() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.stop(0);
        return port;
    }

    private static void allowWebhookHost(int port) {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        Application.getInstance(TenantService.class).update(
                tenant.id(), null, null, null, null, null, null, null, null, null,
                List.of("127.0.0.1:" + port));
    }

    private static List<String> headerNames(String payload) {
        try {
            JsonNode headers = JsonUtils.getMapper().readTree(payload)
                    .get("context").get("http").get("headers");
            List<String> names = new ArrayList<>();
            headers.properties().forEach(entry -> names.add(entry.getKey().toLowerCase(Locale.ROOT)));
            return names;
        } catch (Exception e) {
            throw new IllegalStateException("Could not read envelope headers from: " + payload, e);
        }
    }

    /** Entries are written while the response is rendered, so a short poll avoids flakiness. */
    private static Document awaitEntry(String url) {
        for (int attempt = 0; attempt < 100; attempt++) {
            TenantContext ctx = TenantTestUtils.defaultTenantContext();
            Document entry = Application.getInstance(TenantDatabaseResolver.class)
                    .tenantMetaCollection(ctx, SystemCollections.REQUEST_LOGS)
                    .find(eq("url", url))
                    .sort(com.mongodb.client.model.Sorts.descending("timestamp"))
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

    private static String loginToken(String username, String password) {
        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(username, password))
                .withContentType("application/json")
                .execute();
        String marker = "\"accessToken\":\"";
        int start = login.getContent().indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("Missing accessToken in: " + login.getContent());
        }
        start += marker.length();
        return login.getContent().substring(start, login.getContent().indexOf('"', start));
    }
}
