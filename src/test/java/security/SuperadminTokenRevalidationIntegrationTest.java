package security;

import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import models.CollectionRules;
import models.TenantDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.SystemUserService;
import services.TenantService;
import utils.AdminTestUtils;
import utils.DbUtils;
import utils.TenantTestUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * A superadmin bearer token carries the widest access in the system and lives for an hour, its
 * refresh token for a week. Deleting the account must take effect immediately rather than at the
 * end of that window, so every request has to revalidate that the account still exists - the same
 * guarantee the tenant user path already provides through {@code TenantUserService#resolveUser}.
 */
@ExtendWith({TestRunner.class})
class SuperadminTokenRevalidationIntegrationTest {
    private static final String PASSWORD = "revalidation-password-1";

    @Test
    void deletingASuperadminInvalidatesItsBearerTokenImmediately() {
        SystemUserService systemUsers = Application.getInstance(SystemUserService.class);
        String username = "revalidate-admin-" + DbUtils.id().substring(0, 8);
        String adminId = String.valueOf(systemUsers.createSuperadmin(username, null, PASSWORD).get("id"));

        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        String collection = "revalidation_" + DbUtils.id();
        TenantTestUtils.seedCollection(collection, new CollectionRules("*", "*", "*", "*", "*", "owner"));
        TenantTestUtils.seedRecord(collection, "RECORD");

        String token = tenantScopedToken(username, tenant.id());

        // While the account exists the token works
        assertThat(dataPlaneCall(collection, token).getStatusCode(), equalTo(200));
        assertThat(switchTenant(token, tenant.id()).getStatusCode(), equalTo(200));

        assertThat(systemUsers.deleteSuperadmin(adminId), equalTo(SystemUserService.DeleteOutcome.DELETED));

        // ... and stops working the moment it is gone, without waiting for the token to expire
        assertThat("a deleted superadmin must not reach the data plane",
                dataPlaneCall(collection, token).getStatusCode(), anyOf(equalTo(401), equalTo(403)));
        assertThat("a deleted superadmin must not be able to mint fresh tokens",
                switchTenant(token, tenant.id()).getStatusCode(), anyOf(equalTo(401), equalTo(403)));
    }

    private static TestResponse dataPlaneCall(String collection, String token) {
        return TestRequest.get("/api/collections/" + collection + "?offset=0&limit=5")
                .withHeader("Authorization", "Bearer " + token)
                .execute();
    }

    private static TestResponse switchTenant(String token, String tenantId) {
        return TestRequest.post("/api/admin/switch-tenant")
                .withHeader("Authorization", "Bearer " + token)
                .withStringBody("{\"tenantId\":\"" + tenantId + "\"}")
                .withContentType("application/json")
                .execute();
    }

    /** Signs in for an API token and binds it to a tenant, as the admin UI does. */
    private static String tenantScopedToken(String username, String tenantId) {
        AdminTestUtils.prepareAdminPassword();

        TestResponse login = TestRequest.post("/api/admin/token")
                .withStringBody("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}")
                .withContentType("application/json")
                .execute();

        String token = extract(login.getContent(), "accessToken");
        TestResponse scoped = switchTenant(token, tenantId);
        return extract(scoped.getContent(), "accessToken");
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
