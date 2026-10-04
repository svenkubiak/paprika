package controllers;

import auth.TenantContext;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.ApiKeyDefinition;
import models.CollectionRules;
import models.TenantDefinition;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.ApiKeyService;
import services.RequestLogService;
import services.TenantDatabaseResolver;
import services.UserService;
import utils.AdminTestUtils;
import utils.TenantTestUtils;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@ExtendWith({TestRunner.class})
class ApiKeySourceBindingIntegrationTest {

    /** Both families, since whether {@code localhost} resolves to IPv4 or IPv6 depends on the machine. */
    private static final String LOOPBACK = "\"127.0.0.0/8\",\"::1/128\"";

    /** Documentation ranges (RFC 5737 / RFC 3849) the suite can never originate from. */
    private static final String ELSEWHERE = "\"203.0.113.0/24\",\"2001:db8::/32\"";

    @Test
    void keyWithoutRangesIsUnchanged() {
        String collection = "posts_cidr_unbound";
        TenantTestUtils.seedCollection(collection, authListRules());
        String key = createKey("cidr-unbound-key", user("cidr-unbound-user"), null).key();

        TestResponse response = list(collection, key);

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(storedCidrs("cidr-unbound-key"), empty());
    }

    @Test
    void keyIsAcceptedFromAnAllowedRangeAndRejectedFromAnotherOne() {
        String collection = "posts_cidr_range";
        TenantTestUtils.seedCollection(collection, authListRules());

        String allowed = createKey("cidr-allowed-key", user("cidr-allowed-user"), LOOPBACK).key();
        String elsewhere = createKey("cidr-elsewhere-key", user("cidr-elsewhere-user"), ELSEWHERE).key();

        assertThat(list(collection, allowed).getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(list(collection, elsewhere).getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
    }

    /** Any other answer would confirm to a thief that the stolen secret is real. */
    @Test
    void rejectionIsIndistinguishableFromAnInvalidKey() {
        String collection = "posts_cidr_indistinguishable";
        TenantTestUtils.seedCollection(collection, authListRules());

        String bound = createKey("cidr-indistinct-key", user("cidr-indistinct-user"), ELSEWHERE).key();

        TestResponse rejected = list(collection, bound);
        TestResponse unknown = list(collection, "pk_totally-unknown-key-value-000000000000");

        assertThat(rejected.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat(rejected.getStatusCode(), equalTo(unknown.getStatusCode()));
        assertThat(rejected.getContent(), equalTo(unknown.getContent()));
        assertThat(rejected.getHeader("WWW-Authenticate"), equalTo(unknown.getHeader("WWW-Authenticate")));
        assertThat(rejected.getContentType(), equalTo(unknown.getContentType()));
    }

    /** The address checked is the TCP peer; a caller-supplied header must not decide it. */
    @Test
    void forgedForwardingHeadersCannotLiftTheBinding() {
        String collection = "posts_cidr_forged";
        TenantTestUtils.seedCollection(collection, authListRules());

        String bound = createKey("cidr-forged-key", user("cidr-forged-user"), ELSEWHERE).key();

        TestResponse forged = TestRequest.get("/api/collections/" + collection + "?offset=0&limit=25")
                .withHeader("Authorization", "Bearer " + bound)
                .withHeader("X-Forwarded-For", "203.0.113.7")
                .withHeader("X-Real-IP", "203.0.113.7")
                .withHeader("Forwarded", "for=203.0.113.7")
                .execute();

        assertThat(forged.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));

        String loopback = createKey("cidr-forged-loopback-key", user("cidr-forged-loopback"), LOOPBACK).key();
        TestResponse stillAllowed = TestRequest.get("/api/collections/" + collection + "?offset=0&limit=25")
                .withHeader("Authorization", "Bearer " + loopback)
                .withHeader("X-Forwarded-For", "203.0.113.7")
                .execute();

        assertThat(stillAllowed.getStatusCode(), equalTo(StatusCodes.OK));
    }

    /** The arrival address depends on the machine, so this calls the service as {@code AuthService} does. */
    @Test
    void enforcementCoversBothAddressFamilies() {
        ApiKeyService apiKeyService = Application.getInstance(ApiKeyService.class);
        TenantDefinition tenant = TenantTestUtils.defaultTenant();

        String v4Key = createKey("cidr-v4-key", user("cidr-v4-user"), "\"10.200.0.0/24\"").key();
        assertThat(apiKeyService.resolve(v4Key, address("10.200.0.17")).key().isPresent(), is(true));
        assertThat(apiKeyService.resolve(v4Key, address("10.200.1.17")).key().isPresent(), is(false));
        assertThat(apiKeyService.resolve(v4Key, address("10.200.1.17")).sourceRejected(), is(true));

        String v6Key = createKey("cidr-v6-key", user("cidr-v6-user"),
                "\"2a01:4f8:c17:c74c::/64\"").key();
        assertThat(apiKeyService.resolve(v6Key, address("2a01:4f8:c17:c74c::1")).key().isPresent(), is(true));
        assertThat(apiKeyService.resolve(v6Key, address("2a01:4f8:c17:c74d::1")).key().isPresent(), is(false));
        assertThat(apiKeyService.resolve(v6Key, address("2a01:4f8:c17:c74d::1")).sourceRejected(), is(true));

        assertThat(apiKeyService.resolve(v4Key, null).key().isPresent(), is(false));
        assertThat(apiKeyService.resolve(v4Key, null).sourceRejected(), is(true));

        String open = createKey("cidr-open-key", user("cidr-open-user"), null).key();
        assertThat(apiKeyService.resolve(open, null).key().isPresent(), is(true));
        assertThat(apiKeyService.resolve(open, address("203.0.113.7")).key().isPresent(), is(true));
        assertThat(tenant.id(), notNullValue());
    }

    @Test
    void invalidRangesAreRefusedWhenSavingRatherThanIgnored() {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        String boundId = user("cidr-invalid-user");

        TestResponse created = AdminTestUtils.postWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/api-keys",
                adminCookies(),
                "{\"name\":\"cidr-invalid-key\",\"userId\":\"" + boundId
                        + "\",\"allowedCidrs\":[\"10.200.0.0/24\",\"not-an-address\"]}",
                "application/json");

        assertThat(created.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(created.getContent(), containsString("not-an-address"));

        TestResponse list = AdminTestUtils.getWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/api-keys", adminCookies());
        assertThat(list.getContent(), not(containsString("cidr-invalid-key")));

        String keyId = createKey("cidr-invalid-update-key", user("cidr-invalid-update-user"),
                "\"10.200.0.0/24\"").id();
        TestResponse patched = AdminTestUtils.patchWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/api-keys/" + keyId,
                adminCookies(),
                "{\"allowedCidrs\":[\"10.200.0.0/99\"]}",
                "application/json");

        assertThat(patched.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(storedCidrs("cidr-invalid-update-key"), contains("10.200.0.0/24"));
    }

    /** Unlike {@code bypassRules}/{@code bypassHooks}, this field narrows reach, so it may change later. */
    @Test
    void sourceBindingCanBeChangedAfterwards() {
        String collection = "posts_cidr_editable";
        TenantTestUtils.seedCollection(collection, authListRules());
        TenantDefinition tenant = TenantTestUtils.defaultTenant();

        CreatedKey key = createKey("cidr-editable-key", user("cidr-editable-user"), ELSEWHERE);
        assertThat(list(collection, key.key()).getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));

        assertThat(patchCidrs(tenant, key.id(), "[" + LOOPBACK + "]").getStatusCode(),
                equalTo(StatusCodes.NO_CONTENT));
        assertThat(list(collection, key.key()).getStatusCode(), equalTo(StatusCodes.OK));

        assertThat(patchCidrs(tenant, key.id(), "[]").getStatusCode(), equalTo(StatusCodes.NO_CONTENT));
        assertThat(storedCidrs("cidr-editable-key"), empty());
        assertThat(list(collection, key.key()).getStatusCode(), equalTo(StatusCodes.OK));

        // Host bits are masked off when the range is stored
        assertThat(patchCidrs(tenant, key.id(), "[\"10.200.0.42/24\"]").getStatusCode(),
                equalTo(StatusCodes.NO_CONTENT));
        assertThat(storedCidrs("cidr-editable-key"), contains("10.200.0.0/24"));

        assertThat(patchCidrs(tenant, "missing-key-id", "[]").getStatusCode(),
                equalTo(StatusCodes.NOT_FOUND));
    }

    /** The operator learns the reason from the log; the response still must not reveal it. */
    @Test
    void requestLogNamesTheSourceAsTheReason() {
        String collection = "posts_cidr_log";
        TenantTestUtils.seedCollection(collection, authListRules());
        String rejectedKey = createKey("cidr-logged-key", user("cidr-logged-user"), ELSEWHERE).key();

        assertThat(list(collection, rejectedKey).getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));

        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        Map<String, Object> logs = Application.getInstance(RequestLogService.class)
                .list(ctx, 0, 100, "allowedCidrs", "error", null, null);

        @SuppressWarnings("unchecked")
        List<Document> items = (List<Document>) logs.get("items");
        assertThat("the rejection is recorded as a source rejection, not as an invalid key",
                items.stream().anyMatch(entry ->
                        entry.getString("errorMessage") != null
                                && entry.getString("errorMessage").contains("allowedCidrs")
                                && entry.getString("errorMessage").contains("cidr-logged-key")),
                is(true));
    }

    private record CreatedKey(String id, String key) {}

    private static CreatedKey createKey(String name, String userId, String allowedCidrsJson) {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        String body = "{\"name\":\"" + name + "\",\"userId\":\"" + userId + "\""
                + (allowedCidrsJson == null ? "" : ",\"allowedCidrs\":[" + allowedCidrsJson + "]")
                + "}";

        TestResponse response = AdminTestUtils.postWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/api-keys",
                adminCookies(),
                body,
                "application/json");

        assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.CREATED));
        return new CreatedKey(
                extractJsonString(response.getContent(), "id"),
                extractJsonString(response.getContent(), "key"));
    }

    private static TestResponse patchCidrs(TenantDefinition tenant, String keyId, String cidrsJson) {
        return AdminTestUtils.patchWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/api-keys/" + keyId,
                adminCookies(),
                "{\"allowedCidrs\":" + cidrsJson + "}",
                "application/json");
    }

    private static List<String> storedCidrs(String keyName) {
        Document stored = Application.getInstance(TenantDatabaseResolver.class)
                .systemCollection(ApiKeyDefinition.COLLECTION)
                .find(new Document("name", keyName))
                .first();

        assertThat(stored, notNullValue());
        List<String> cidrs = stored.getList("allowedCidrs", String.class);
        return cidrs == null ? List.of() : cidrs;
    }

    private static InetAddress address(String literal) {
        return InetAddress.ofLiteral(literal);
    }

    private static TestResponse list(String collection, String key) {
        return TestRequest.get("/api/collections/" + collection + "?offset=0&limit=25")
                .withHeader("Authorization", "Bearer " + key)
                .execute();
    }

    private static String user(String username) {
        return String.valueOf(Application.getInstance(UserService.class)
                .createUser(username, null, "secret-password-123")
                .get("id"));
    }

    private static AdminTestUtils.AdminCookies adminCookies() {
        return new AdminTestUtils.AdminCookies(AdminTestUtils.loginAsAdmin(), null);
    }

    private static CollectionRules authListRules() {
        return new CollectionRules("auth", "auth", null, null, null, null);
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
