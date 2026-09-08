package controllers;

import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.TenantDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantService;
import services.TenantUserService;
import services.UserService;
import utils.AdminTestUtils;
import utils.TenantTestUtils;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@ExtendWith({TestRunner.class})
class AuthControllerTest {

    @BeforeEach
    void disableRegistrationOnDefaultTenant() {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        Application.getInstance(TenantService.class).update(tenant.id(), null, null, null, false, null, null, null, null);
    }

    @Test
    void loginReturnsAccessAndRefreshToken() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("token-user", "token@example.com", "secret-password-123");

        TestResponse response = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("token-user", "secret-password-123"))
                .withContentType("application/json")
                .execute();

        assertThat(response, not(nullValue()));
        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), containsString("\"accessToken\""));
        assertThat(response.getContent(), containsString("\"refreshToken\""));
    }

    @Test
    void refreshRejectsDeletedUser() {
        UserService userService = Application.getInstance(UserService.class);
        Map<String, Object> user = userService.createUser("deleted-refresh-user", null, "secret-password-123");

        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("deleted-refresh-user", "secret-password-123"))
                .withContentType("application/json")
                .execute();
        String refreshToken = extractJsonString(login.getContent(), "refreshToken");

        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        Application.getInstance(TenantUserService.class).deleteUser(tenant, String.valueOf(user.get("id")));

        TestResponse refresh = TestRequest.post("/api/auth/refresh")
                .withStringBody("{\"refreshToken\":\"" + refreshToken + "\"}")
                .withContentType("application/json")
                .execute();

        assertThat(refresh.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
    }

    @Test
    void registerIsDisabledByDefault() {
        TestResponse response = TestRequest.post("/api/auth/register")
                .withStringBody(registerBody("default", "new-user", "secret-password-123", "new@example.com"))
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
        assertThat(response.getContent(), containsString("Registration is disabled"));
    }

    @Test
    void registerCreatesUserWhenEnabledForTenant() {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        Application.getInstance(TenantService.class).update(tenant.id(), null, null, null, true, null, null, null, null);

        TestResponse response = TestRequest.post("/api/auth/register")
                .withStringBody(registerBody(tenant.slug(), "registered-user", "secret-password-123", "reg@example.com"))
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.CREATED));
        assertThat(response.getContent(), containsString("\"username\":\"registered-user\""));
        assertThat(response.getContent(), containsString("\"role\":\"user\""));
    }

    @Test
    void registerRequiresTenantInBody() {
        TestResponse response = TestRequest.post("/api/auth/register")
                .withStringBody("{\"username\":\"new-user\",\"password\":\"secret123\"}")
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("Tenant slug is required"));
    }

    @Test
    void registerRequiresExistingTenant() {
        TestResponse response = TestRequest.post("/api/auth/register")
                .withStringBody(registerBody("missing-tenant", "new-user", "secret-password-123", null))
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("Tenant not found"));
    }

    @Test
    void loginRejectsInvalidCredentials() {
        TestResponse response = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("missing", "nope"))
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
    }

    @Test
    void loginRejectsSuperadminCredentials() {
        TestResponse response = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("admin", "admin"))
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
    }

    @Test
    void adminTokenReturnsAccessAndRefreshToken() {
        String password = AdminTestUtils.prepareAdminPassword();
        TestResponse response = TestRequest.post("/api/admin/token")
                .withStringBody(TenantTestUtils.loginBody("admin", password))
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), containsString("\"accessToken\""));
        assertThat(response.getContent(), containsString("\"refreshToken\""));
    }

    private String registerBody(String tenant, String username, String password, String email) {
        String emailPart = email == null ? "" : ",\"email\":\"" + email + "\"";
        return "{\"tenant\":\"" + tenant + "\",\"username\":\"" + username + "\",\"password\":\"" + password + "\"" + emailPart + "}";
    }

    private static String extractJsonString(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("Field not found: " + field);
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
