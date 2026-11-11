package services;

import auth.TenantContext;
import com.sun.net.httpserver.HttpServer;
import constants.GlobalHooks;
import constants.SystemCollections;
import hooks.HookTestResult;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import models.CollectionDefinition;
import models.CollectionRules;
import models.FieldDefinition;
import models.HookDefinition;
import models.HookEvent;
import models.TenantDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.DbUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * The admin "test hook" button is the only caller of the sample payloads. Each event has to produce
 * a body a receiver can answer, and a hook that fails validation must report that instead of calling out.
 */
@ExtendWith({TestRunner.class})
class HookServiceProbeTest {

    @Test
    void everyEventReachesTheAllowlistedTargetWithItsSample() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(calls);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        try {
            int port = server.getAddress().getPort();
            String url = "http://127.0.0.1:" + port + "/hook";
            TenantContext ctx = tenant("127.0.0.1:" + port);
            String posts = seedPosts(ctx);

            HookService hooks = Application.getInstance(HookService.class);
            for (HookEvent event : HookEvent.values()) {
                String collection = collectionFor(event, posts);
                HookTestResult result = hooks.testHook(ctx, collection, hook(
                        event, url, "POST", 1000, "probe-secret",
                        event == HookEvent.beforeRequest ? Boolean.TRUE : null,
                        null, null, null, null));

                assertThat(event.name(), result.error(), nullValue());
                assertThat(event.name(), result.statusCode(), is(200));
                assertThat(event.name(), result.responseBody(), is("pong"));
                assertThat(event.name(), result.signature(), not(nullValue()));
                assertThat(event.name(), result.requestPayload(), containsString("\"path\":\"" + pathFor(event, posts) + "\""));
                assertThat(event.name(), result.requestPayload(), containsString(marker(event)));
                assertThat(event.name(), result.requestPayload(), containsString("\"schema\":null"));
            }
            assertThat(calls.get(), is(HookEvent.values().length));

            HookTestResult withSchema = hooks.testHook(ctx, posts, hook(
                    HookEvent.afterCreate, url, "POST", 1000, "probe-secret",
                    null, null, null, Boolean.TRUE, null));
            assertThat(withSchema.error(), nullValue());
            assertThat(withSchema.requestPayload(), containsString("\"schema\":{"));
            assertThat(withSchema.requestPayload(), containsString("\"name\":\"title\""));

            HookTestResult scoped = hooks.testHook(ctx, GlobalHooks.COLLECTION, hook(
                    HookEvent.beforeRequest, url, "POST", 1000, "probe-secret",
                    Boolean.FALSE, List.of(posts), null, null, null));
            assertThat(scoped.error(), nullValue());
            assertThat(scoped.requestPayload(), containsString("\"path\":\"/api/auth/login\""));

            for (String method : List.of("GET", "PUT", "PATCH", "DELETE")) {
                HookTestResult sent = hooks.testHook(ctx, posts, hook(
                        HookEvent.afterCreate, url, method, 1000, "probe-secret",
                        null, null, null, null, null));
                assertThat(method, sent.error(), nullValue());
                assertThat(method, sent.statusCode(), is(200));
            }

            int afterSuccess = calls.get();
            HookTestResult blocked = hooks.testHook(ctx, posts, hook(
                    HookEvent.afterCreate, url, "POST", 1000, "probe-secret",
                    null, null, List.of("Authorization"), null, null));
            assertThat(blocked.error(), containsString("cannot be forwarded"));
            assertThat(calls.get(), is(afterSuccess));
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void aProbeThatCannotBeSentReportsTheReason() throws Exception {
        HttpServer server = server(new AtomicInteger());
        server.start();
        try {
            int port = server.getAddress().getPort();
            String url = "http://127.0.0.1:" + port + "/hook";
            TenantContext ctx = tenant("127.0.0.1:" + port);
            String posts = seedPosts(ctx);
            HookService hooks = Application.getInstance(HookService.class);

            assertThat(hooks.testHook(ctx, "missing-" + posts, hook(
                    HookEvent.afterCreate, url, "POST", 1000, "probe-secret",
                    null, null, null, null, null)).error(), is("Collection not found"));

            assertThat(hooks.testHook(ctx, posts, hook(
                    HookEvent.afterCreate, url, "POST", 1000, " ",
                    null, null, null, null, null)).error(), containsString("signing secret"));

            assertThat(hooks.testHook(ctx, posts, hook(
                    HookEvent.afterCreate, "ftp://127.0.0.1:" + port + "/hook", "POST", 1000, "probe-secret",
                    null, null, null, null, null)).error(), containsString("http or https"));

            assertThat(hooks.testHook(ctx, posts, hook(
                    HookEvent.beforeRequest, url, "POST", 1000, "probe-secret",
                    Boolean.TRUE, null, null, null, null)).error(), containsString("global collection scope"));

            assertThat(hooks.testHook(ctx, GlobalHooks.COLLECTION, hook(
                    HookEvent.afterCreate, url, "POST", 1000, "probe-secret",
                    null, null, null, null, null)).error(), containsString("Only beforeRequest"));

            assertThat(hooks.testHook(ctx, GlobalHooks.COLLECTION, hook(
                    HookEvent.beforeRequest, url, "POST", 1000, "probe-secret",
                    null, null, null, null, null)).error(), containsString("applyToAllCollections"));

            assertThat(hooks.testHook(ctx, GlobalHooks.COLLECTION, hook(
                    HookEvent.beforeRequest, url, "POST", 1000, "probe-secret",
                    Boolean.FALSE, List.of(), null, null, null)).error(), containsString("at least one collection"));

            assertThat(hooks.testHook(ctx, posts, hook(
                    HookEvent.beforeLogin, url, "POST", 1000, "probe-secret",
                    null, null, null, null, null)).error(), containsString("only allowed on the users collection"));

            assertThat(hooks.testHook(ctx, posts, hook(
                    HookEvent.afterCreate, url, "TRACE", 1000, "probe-secret",
                    null, null, null, null, null)).error(), containsString("Hook method must be one of"));

            assertThat(hooks.testHook(ctx, posts, hook(
                    HookEvent.afterCreate, url, "POST", 1000, "probe-secret",
                    null, null, null, null, Boolean.TRUE)).error(), containsString("only allowed for beforeRequest"));

            assertThat(hooks.testHook(ctx, posts, hook(
                    HookEvent.afterCreate, url, "POST", 30_001, "probe-secret",
                    null, null, null, null, null)).error(), containsString("must not exceed"));

            assertThat(hooks.parseBody(null).size(), is(0));
            assertThat(hooks.parseBody("{").size(), is(0));
            assertThat(hooks.parseBody("{\"username\":\"ada\"}").get("username").asString(), is("ada"));
            assertThat(hooks.writeBody(hooks.parseBody("{\"username\":\"ada\"}")), containsString("ada"));
            assertThat(hooks.parseBody("{\"username\":\"ada\"}").size(), greaterThan(0));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server(AtomicInteger calls) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            try (InputStream in = exchange.getRequestBody()) {
                in.readAllBytes();
            }
            byte[] body = "pong".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        return server;
    }

    private static TenantContext tenant(String allowlistEntry) {
        TenantService tenants = Application.getInstance(TenantService.class);
        String slug = "probe-" + Long.toHexString(System.nanoTime());
        TenantDefinition tenant = tenants.create("Hook probe " + slug, slug);
        tenants.update(tenant.id(), null, null, null, null, null, null, null, null, null, List.of(allowlistEntry));
        return TenantContext.guest(tenant.id(), tenant.databaseName());
    }

    private static String seedPosts(TenantContext ctx) {
        String posts = "probe_posts_" + Long.toHexString(System.nanoTime());
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        collections.insertDefinition(ctx, definition(posts, new FieldDefinition(
                "title", enums.FieldType.STRING, false, true, null)));
        if (collections.findDefinition(ctx, SystemCollections.USERS) == null) {
            throw new IllegalStateException("A new tenant has no users collection");
        }
        return posts;
    }

    private static CollectionDefinition definition(String name, FieldDefinition field) {
        return new CollectionDefinition(
                DbUtils.id(), name, List.of(field), List.of(), CollectionRules.locked(), false);
    }

    private static String collectionFor(HookEvent event, String posts) {
        if (event == HookEvent.beforeRequest) {
            return GlobalHooks.COLLECTION;
        }
        if (event.isAuthEvent()) {
            return SystemCollections.USERS;
        }
        return posts;
    }

    private static String pathFor(HookEvent event, String posts) {
        return switch (event) {
            case beforeRequest, beforeLogin, afterLogin -> "/api/auth/login";
            case beforeRegister, afterRegister -> "/api/auth/register";
            case beforeRefresh, afterRefresh -> "/api/auth/refresh";
            default -> "/api/collections/" + posts;
        };
    }

    private static String marker(HookEvent event) {
        return switch (event) {
            case beforeCreate, afterCreate -> "Lorem ipsum";
            case beforeUpdate, afterUpdate -> "New Title";
            case beforeRegister, afterRegister -> "demo@example.com";
            case beforeRequest, beforeLogin, afterLogin -> "\"username\":\"demo\"";
            case beforeView, beforeDelete, afterDelete -> "Hello World";
            case beforeList, beforeRefresh, afterRefresh -> "\"record\":null";
        };
    }

    private static HookDefinition hook(
            HookEvent event,
            String url,
            String method,
            Integer timeoutMs,
            String secret,
            Boolean applyToAll,
            List<String> targets,
            List<String> forwardHeaders,
            Boolean includeSchema,
            Boolean includeFileRoutes) {

        return new HookDefinition(
                null,
                null,
                null,
                "ignored",
                event,
                url,
                method,
                timeoutMs,
                secret,
                null,
                true,
                10,
                includeSchema,
                null,
                applyToAll,
                targets,
                forwardHeaders,
                includeFileRoutes);
    }
}
