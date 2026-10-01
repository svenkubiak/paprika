package security;

import auth.TenantContext;
import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.routing.Router;
import io.mangoo.routing.routes.RequestRoute;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import models.CollectionRules;
import models.FieldDefinition;
import models.TenantDefinition;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.ApiKeyService;
import services.TenantCollectionService;
import services.TenantService;
import services.TenantUserService;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.mongodb.client.model.Filters.eq;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Walks every registered request route as tenant A with tenant B parameters, so new routes are covered
 * by default. Both tenants share collection name, record id and username with public rules, so only
 * tenant scoping itself can prevent a leak.
 */
@ExtendWith({TestRunner.class})
class TenantIsolationIntegrationTest {
    private static final String SHARED_COLLECTION = "iso_shared";
    private static final String SHARED_USERNAME = "iso-user";
    private static final String PASSWORD_A = "isolation-password-aaa-1";
    private static final String PASSWORD_B = "isolation-password-bbb-2";
    private static final String TENANT_A_RECORD = "TENANT-A-RECORD";
    private static final String TENANT_B_SECRET = "TENANT-B-SECRET";

    private static final String PLACEHOLDER_FIELD = "attachment";
    private static final String PLACEHOLDER_FILE_ID = "iso-file-id";

    private static String sharedRecordId;
    private static TenantDefinition tenantA;
    private static TenantDefinition tenantB;
    private static String tenantBUserId;
    private static String tenantBApiKeyId;
    private static String tenantBApiKeyPlaintext;
    private static String tokenA;

    @BeforeAll
    static void setUp() {
        tenantA = TenantTestUtils.defaultTenant();
        tenantB = Application.getInstance(TenantService.class)
                .create("Isolation B", "iso-b-" + DbUtils.id().substring(0, 8));

        sharedRecordId = DbUtils.id();

        seedCollection(contextOf(tenantA));
        seedCollection(contextOf(tenantB));
        seedRecord(contextOf(tenantA), TENANT_A_RECORD);
        seedRecord(contextOf(tenantB), TENANT_B_SECRET);

        TenantUserService users = Application.getInstance(TenantUserService.class);
        users.createUser(tenantA, SHARED_USERNAME, null, PASSWORD_A);
        tenantBUserId = String.valueOf(users.createUser(tenantB, SHARED_USERNAME, null, PASSWORD_B).get("id"));

        // A key of tenant B, so API key routes hit another tenant's key and its plaintext is a leak marker
        ApiKeyService.CreatedApiKey keyB = Application.getInstance(ApiKeyService.class)
                .create(tenantB, "iso-b-key", tenantBUserId, null);
        tenantBApiKeyId = String.valueOf(keyB.key().get("id"));
        tenantBApiKeyPlaintext = keyB.plaintext();

        tokenA = login(tenantA.slug(), PASSWORD_A);
    }

    @Test
    void noRegisteredRouteLeaksAnotherTenant() {
        List<RequestRoute> routes = Router.getRequestRoutes().toList();
        assertThat("the routing table must be readable, otherwise this test proves nothing",
                routes, not(empty()));

        Set<String> exercised = new LinkedHashSet<>();
        List<String> leaks = new ArrayList<>();

        for (RequestRoute route : routes) {
            for (String method : methodsOf(route)) {
                // Each call may mutate tenant A data through a public rule, so restore first
                restoreRecords();

                String uri = concreteUri(route.getUrl());
                TestResponse response = call(method, uri);
                exercised.add(method + " " + route.getUrl());

                String leaked = findLeak(response.getContent());
                if (leaked != null) {
                    leaks.add(method + " " + uri + " -> leaked '" + leaked + "' (status "
                            + response.getStatusCode() + "): " + abbreviate(response.getContent()));
                }

                // Logout revokes the caller's tokens; every later route would only see a 401 and prove nothing
                if ("/api/auth/logout".equals(route.getUrl())) {
                    tokenA = login(tenantA.slug(), PASSWORD_A);
                }
            }
        }

        // A route that cannot be called here is not proven tenant-safe, so skipping it must fail
        assertThat("every registered route must be exercised", exercised, hasSize(routes.size()));
        assertThat(String.join(System.lineSeparator(), leaks), leaks, is(empty()));

        assertThat("a call as tenant A must never delete a tenant B record",
                recordOf(tenantB), notNullValue());
        assertThat("a call as tenant A must never modify a tenant B record",
                recordOf(tenantB).getString("title"), equalTo(TENANT_B_SECRET));
    }

    @Test
    void dataPlaneReadsAreScopedToTheCallersTenant() {
        // The sweep includes DELETE and only restores before a call, so restore to stay order-independent.
        restoreRecords();

        TestResponse list = call("GET", "/api/collections/" + SHARED_COLLECTION + "?offset=0&limit=25");
        assertThat(list.getStatusCode(), equalTo(200));
        assertThat(list.getContent(), containsString(TENANT_A_RECORD));
        assertThat(list.getContent(), not(containsString(TENANT_B_SECRET)));
        assertThat("both tenants hold one record under the same id",
                list.getContent(), containsString("\"total\":1"));

        TestResponse view = call("GET", "/api/collections/" + SHARED_COLLECTION + "/" + sharedRecordId);
        assertThat(view.getStatusCode(), equalTo(200));
        assertThat(view.getContent(), containsString(TENANT_A_RECORD));
        assertThat(view.getContent(), not(containsString(TENANT_B_SECRET)));
    }

    @Test
    void dataPlaneWritesNeverReachAnotherTenant() {
        restoreRecords();

        TestResponse update = TestRequest.patch("/api/collections/" + SHARED_COLLECTION + "/" + sharedRecordId)
                .withHeader("Authorization", "Bearer " + tokenA)
                .withStringBody("{\"title\":\"CHANGED-BY-A\"}")
                .withContentType("application/json")
                .execute();
        assertThat(update.getStatusCode(), equalTo(200));
        assertThat(recordOf(tenantA).getString("title"), equalTo("CHANGED-BY-A"));
        assertThat("the identically named record of the other tenant must be untouched",
                recordOf(tenantB).getString("title"), equalTo(TENANT_B_SECRET));

        TestResponse delete = call("DELETE", "/api/collections/" + SHARED_COLLECTION + "/" + sharedRecordId);
        assertThat(delete.getStatusCode(), equalTo(200));
        assertThat(recordOf(tenantA), nullValue());
        assertThat("deleting in one tenant must not delete the same id in another",
                recordOf(tenantB), notNullValue());

        restoreRecords();
    }

    @Test
    void credentialsAreBoundToTheirTenant() {
        TestResponse crossTenant = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(tenantB.slug(), SHARED_USERNAME, PASSWORD_A))
                .withContentType("application/json")
                .execute();
        assertThat(crossTenant.getStatusCode(), not(equalTo(200)));
        assertThat(crossTenant.getContent(), not(containsString("accessToken")));

        String tokenB = login(tenantB.slug(), PASSWORD_B);
        TestResponse asB = TestRequest.get("/api/collections/" + SHARED_COLLECTION + "?offset=0&limit=25")
                .withHeader("Authorization", "Bearer " + tokenB)
                .execute();
        assertThat(asB.getContent(), containsString(TENANT_B_SECRET));
        assertThat(asB.getContent(), not(containsString(TENANT_A_RECORD)));

        TestResponse meA = call("GET", "/api/auth/me");
        assertThat(meA.getStatusCode(), equalTo(200));
        assertThat(meA.getContent(), not(containsString(tenantBUserId)));
    }

    @Test
    void metaApiIsUnreachableWithATenantBearerToken() {
        // The meta API is reserved for the admin UI session; a tenant token must not read or reshape schemas and hooks
        for (String uri : List.of(
                "/api/meta/collections/" + SHARED_COLLECTION,
                "/api/meta/tenants",
                "/api/meta/tenants/" + tenantB.id(),
                "/api/meta/tenants/" + tenantB.id() + "/users",
                "/api/meta/tenants/" + tenantB.id() + "/api-keys",
                "/api/meta/global-hooks",
                "/api/meta/schema/export",
                "/api/admin/settings",
                "/api/admin/profile",
                "/api/admin/superadmins",
                "/api/admin/request-logs",
                "/api/admin/backup/export")) {

            TestResponse response = call("GET", uri);
            assertThat(uri + " must not be reachable with a tenant token",
                    response.getStatusCode(), anyOf(equalTo(401), equalTo(403), equalTo(404)));
            assertThat(uri + " must not leak tenant B data", findLeak(response.getContent()), nullValue());
        }

        // Imports listed explicitly: they reshape a tenant or replace the instance
        for (String uri : List.of("/api/meta/schema/import", "/api/admin/backup/import")) {
            TestResponse response = call("POST", uri);
            assertThat(uri + " must not be reachable with a tenant token",
                    response.getStatusCode(), anyOf(equalTo(401), equalTo(403), equalTo(404)));
            assertThat(uri + " must not leak tenant B data", findLeak(response.getContent()), nullValue());
        }
    }

    private static String findLeak(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        for (String marker : List.of(
                TENANT_B_SECRET,
                tenantB.databaseName(),
                tenantBUserId,
                tenantBApiKeyId,
                tenantBApiKeyPlaintext)) {
            if (marker != null && !marker.isBlank() && content.contains(marker)) {
                return marker;
            }
        }
        return null;
    }

    private static List<String> methodsOf(RequestRoute route) {
        List<String> methods = new ArrayList<>();
        if (route.getMethod() != null) {
            methods.add(route.getMethod().toString());
        }
        if (route.getMethods() != null) {
            for (var method : route.getMethods()) {
                methods.add(method.toString());
            }
        }
        return methods.isEmpty() ? List.of("GET") : methods;
    }

    private static String concreteUri(String url) {
        return url
                .replace("{collection}", SHARED_COLLECTION)
                .replace("{id}", sharedRecordId)
                .replace("{field}", PLACEHOLDER_FIELD)
                .replace("{fileId}", PLACEHOLDER_FILE_ID)
                .replace("{tenantId}", tenantB.id())
                .replace("{userId}", tenantBUserId)
                .replace("{keyId}", tenantBApiKeyId);
    }

    private static TestResponse call(String method, String uri) {
        TestResponse request = TestRequest.create(uri, method)
                .withHeader("Authorization", "Bearer " + tokenA)
                .withDisabledRedirects();

        if (List.of("POST", "PUT", "PATCH").contains(method)) {
            request = request.withStringBody("{}").withContentType("application/json");
        }

        return request.execute();
    }

    private static void seedCollection(TenantContext ctx) {
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        if (collections.findDefinition(ctx, SHARED_COLLECTION) != null) {
            return;
        }
        collections.insertDefinition(ctx, new models.CollectionDefinition(
                DbUtils.id(),
                SHARED_COLLECTION,
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition(PLACEHOLDER_FIELD, FieldType.FILE, false, true, null)),
                List.of(),
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                false));
    }

    private static void seedRecord(TenantContext ctx, String title) {
        Application.getInstance(TenantCollectionService.class)
                .dataCollection(ctx, SHARED_COLLECTION)
                .insertOne(new Document().append("id", sharedRecordId).append("title", title));
    }

    private static void restoreRecords() {
        restoreRecord(contextOf(tenantA), TENANT_A_RECORD);
        restoreRecord(contextOf(tenantB), TENANT_B_SECRET);
    }

    private static void restoreRecord(TenantContext ctx, String title) {
        var collection = Application.getInstance(TenantCollectionService.class).dataCollection(ctx, SHARED_COLLECTION);
        collection.deleteMany(eq("id", sharedRecordId));
        collection.insertOne(new Document().append("id", sharedRecordId).append("title", title));
    }

    private static Document recordOf(TenantDefinition tenant) {
        return Application.getInstance(TenantCollectionService.class)
                .dataCollection(contextOf(tenant), SHARED_COLLECTION)
                .find(eq("id", sharedRecordId))
                .first();
    }

    private static TenantContext contextOf(TenantDefinition tenant) {
        return TenantContext.guest(tenant.id(), tenant.databaseName());
    }

    private static String login(String tenantSlug, String password) {
        TestResponse response = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(tenantSlug, SHARED_USERNAME, password))
                .withContentType("application/json")
                .execute();

        String marker = "\"accessToken\":\"";
        int start = response.getContent().indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("Login failed for tenant " + tenantSlug + ": " + response.getContent());
        }
        start += marker.length();
        return response.getContent().substring(start, response.getContent().indexOf('"', start));
    }

    private static String abbreviate(String content) {
        return content.length() > 300 ? content.substring(0, 300) + "..." : content;
    }
}
