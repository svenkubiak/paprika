package controllers;

import auth.TenantContext;
import com.sun.net.httpserver.HttpServer;
import constants.GlobalHooks;
import constants.SystemCollections;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.HookDefinition;
import models.HookEvent;
import models.TenantDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import services.TenantService;
import services.TenantUserService;
import services.UserService;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * A tenant's blocking beforeLogin hook only holds if every login into the tenant runs it, including
 * a login without a slug whose tenant is resolved from the username.
 */
@ExtendWith({TestRunner.class})
class LoginHookTenantIntegrationTest {
    private static final String PASSWORD = "login-hook-password-123";
    private static final String SLUG = "login-hook-tenant-c";
    private static final String BLOCKING = "{\"continue\": false, \"error\": {\"status\": 403, \"message\": \"account suspended\"}}";
    private static final String PASSING = "{\"continue\": true}";

    @Test
    void leavingTheSlugOutDoesNotGetPastTheTenantsBeforeLoginGate() throws IOException {
        TenantDefinition tenantC = tenantC();
        String username = "login-hook-user-" + DbUtils.id();
        Application.getInstance(TenantUserService.class).createUser(tenantC, username, null, PASSWORD);

        Recorder gate = Recorder.start(BLOCKING);
        try (Installed ignored = install(tenantC, beforeLogin(gate), gate)) {
            TestResponse withSlug = login(SLUG, username);
            assertThat(withSlug.getContent(), withSlug.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
            assertThat(withSlug.getContent(), containsString("account suspended"));

            TestResponse withoutSlug = login(null, username);
            assertThat("a login without a slug is not a way into another tenant",
                    withoutSlug.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
            assertThat(withoutSlug.getContent(), not(containsString("accessToken")));
        } finally {
            gate.stop();
        }
    }

    @Test
    void anUnknownSlugRunsNoHookAtAll() throws IOException {
        TenantDefinition defaultTenant = TenantTestUtils.defaultTenant();
        Recorder defaultHooks = Recorder.start(PASSING);
        try (Installed ignored = install(defaultTenant, List.of(beforeLogin(defaultHooks), globalBeforeRequest(defaultHooks)), defaultHooks)) {
            TestResponse response = login("login-hook-no-such-tenant", "whoever");

            assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
            assertThat("the default tenant's hooks must not see a login for another tenant",
                    defaultHooks.calls(), is(0));
        } finally {
            defaultHooks.stop();
        }
    }

    @Test
    void aSlugLessLoginRunsTheDefaultTenantsHooks() throws IOException {
        String username = "login-hook-default-" + DbUtils.id();
        Application.getInstance(UserService.class).createUser(username, null, PASSWORD);

        Recorder defaultHooks = Recorder.start(PASSING);
        try (Installed ignored = install(TenantTestUtils.defaultTenant(), beforeLogin(defaultHooks), defaultHooks)) {
            TestResponse response = login(null, username);

            assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(defaultHooks.calls(), is(1));
        } finally {
            defaultHooks.stop();
        }
    }

    @Test
    void aBearerOfAnotherTenantDoesNotChooseTheHooks() throws IOException {
        TenantDefinition tenantC = tenantC();
        String userOfC = "login-hook-bearer-" + DbUtils.id();
        Application.getInstance(TenantUserService.class).createUser(tenantC, userOfC, null, PASSWORD);
        String bearerOfC = extract(login(SLUG, userOfC).getContent(), "accessToken");

        String userOfDefault = "login-hook-bearer-default-" + DbUtils.id();
        Application.getInstance(UserService.class).createUser(userOfDefault, null, PASSWORD);

        Recorder hooksOfC = Recorder.start(PASSING);
        try (Installed ignored = install(tenantC, List.of(beforeLogin(hooksOfC), globalBeforeRequest(hooksOfC)), hooksOfC)) {
            TestResponse response = TestRequest.post("/api/auth/login")
                    .withHeader("Authorization", "Bearer " + bearerOfC)
                    .withStringBody(TenantTestUtils.loginBody(userOfDefault, PASSWORD))
                    .withContentType("application/json")
                    .execute();

            assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat("a login into the default tenant must not run tenant C's hooks", hooksOfC.calls(), is(0));
        } finally {
            hooksOfC.stop();
        }
    }

    private static TenantDefinition tenantC() {
        TenantService tenants = Application.getInstance(TenantService.class);
        return tenants.findBySlug(SLUG).orElseGet(() -> tenants.create("Login Hook Tenant C", SLUG));
    }

    private static TestResponse login(String slug, String username) {
        String body = slug == null
                ? TenantTestUtils.loginBody(username, PASSWORD)
                : TenantTestUtils.loginBody(slug, username, PASSWORD);
        return TestRequest.post("/api/auth/login").withStringBody(body).withContentType("application/json").execute();
    }

    private static HookDefinition beforeLogin(Recorder recorder) {
        return hook(SystemCollections.USERS, HookEvent.beforeLogin, recorder, null);
    }

    private static HookDefinition globalBeforeRequest(Recorder recorder) {
        return hook(GlobalHooks.COLLECTION, HookEvent.beforeRequest, recorder, true);
    }

    private static HookDefinition hook(String collection, HookEvent event, Recorder recorder, Boolean allCollections) {
        return new HookDefinition(
                DbUtils.id(), "login-hook-tenant-test", null, collection, event, recorder.url(),
                null, null, "test-secret", null, true, null, null, false, allCollections, null, null, null);
    }

    private static Installed install(TenantDefinition tenant, HookDefinition hook, Recorder recorder) {
        return install(tenant, List.of(hook), recorder);
    }

    private static Installed install(TenantDefinition tenant, List<HookDefinition> hooks, Recorder recorder) {
        TenantService tenants = Application.getInstance(TenantService.class);
        tenants.update(tenant.id(), null, null, null, null, null, null, null, null, null, List.of(recorder.hostPort()));
        TenantContext ctx = TenantContext.guest(tenant.id(), tenant.databaseName());
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        hooks.forEach(hook -> collections.insertHook(ctx, hook));
        return () -> {
            hooks.forEach(hook -> collections.deleteHook(ctx, hook.id()));
            tenants.update(tenant.id(), null, null, null, null, null, null, null, null, null, List.of());
        };
    }

    private interface Installed extends AutoCloseable {
        @Override
        void close();
    }

    private record Recorder(HttpServer server, AtomicInteger counter) {
        static Recorder start(String answer) throws IOException {
            AtomicInteger counter = new AtomicInteger();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/hook", exchange -> {
                exchange.getRequestBody().readAllBytes();
                counter.incrementAndGet();
                byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            server.start();
            return new Recorder(server, counter);
        }

        int calls() {
            return counter.get();
        }

        String hostPort() {
            return "127.0.0.1:" + server.getAddress().getPort();
        }

        String url() {
            return "http://" + hostPort() + "/hook";
        }

        void stop() {
            server.stop(0);
        }
    }

    private static String extract(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("Missing " + field + " in: " + json);
        }
        start += marker.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
