package controllers;

import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.TenantDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantService;
import services.UserService;
import utils.AdminTestUtils;
import utils.TenantTestUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@ExtendWith({TestRunner.class})
class TenantUsersIntegrationTest {

    @Test
    void superadminCanListCreateAndDeleteTenantUsers() {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        TestResponse listEmpty = AdminTestUtils.getWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/users",
                cookies);
        assertThat(listEmpty.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(listEmpty.getContent(), not(containsString("\"username\":\"admin-created\"")));

        TestResponse create = AdminTestUtils.postWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/users",
                cookies,
                "{\"username\":\"admin-created\",\"password\":\"secret-password-123\",\"email\":\"admin@example.com\"}",
                "application/json");
        assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));
        assertThat(create.getContent(), containsString("\"username\":\"admin-created\""));

        TestResponse list = AdminTestUtils.getWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/users",
                cookies);
        assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(list.getContent(), containsString("\"username\":\"admin-created\""));

        String userId = extractJsonString(list.getContent(), "id");

        TestResponse update = AdminTestUtils.patchWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/users/" + userId,
                cookies,
                "{\"username\":\"admin-updated\",\"email\":\"updated@example.com\"}",
                "application/json");
        assertThat(update.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(update.getContent(), containsString("\"username\":\"admin-updated\""));
        assertThat(update.getContent(), containsString("\"email\":\"updated@example.com\""));

        TestResponse delete = AdminTestUtils.deleteWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/users/" + userId,
                cookies);
        assertThat(delete.getStatusCode(), equalTo(StatusCodes.NO_CONTENT));
    }

    @Test
    void tenantBearerTokenCannotManageUsers() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("users-meta-user", null, "secret-password-123");

        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("users-meta-user", "secret-password-123"))
                .withContentType("application/json")
                .execute();

        String accessToken = extractJsonString(login.getContent(), "accessToken");
        TenantDefinition tenant = TenantTestUtils.defaultTenant();

        TestResponse response = TestRequest.get("/api/meta/tenants/" + tenant.id() + "/users")
                .withHeader("Authorization", "Bearer " + accessToken)
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
    }

    @Test
    void superadminCanToggleRegistrationEnabled() {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        TestResponse update = AdminTestUtils.patchWithAdminCookies(
                "/api/meta/tenants/" + tenant.id(),
                cookies,
                "{\"registrationEnabled\":true}",
                "application/json");
        assertThat(update.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(update.getContent(), containsString("\"registrationEnabled\":true"));

        TenantDefinition reloaded = Application.getInstance(TenantService.class)
                .findById(tenant.id())
                .orElseThrow();
        assertThat(reloaded.registrationEnabled(), equalTo(true));
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
