package controllers;

import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.mangoo.utils.CommonUtils;
import io.undertow.util.StatusCodes;
import models.TenantDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantService;
import services.TenantUserService;
import services.UserService;
import utils.TenantTestUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@ExtendWith({TestRunner.class})
class TenantAuthRecoveryIntegrationTest {

    private static final String OLD_PASSWORD = "old-secret-password-123";
    private static final String NEW_PASSWORD = "new-secret-password-123";

    @Test
    void passwordResetChangesThePasswordAndTokenIsSingleUse() {
        setFlags(true, null, null);
        String username = "reset-" + CommonUtils.uuidV7();
        String email = username + "@example.com";
        Application.getInstance(UserService.class).createUser(username, email, OLD_PASSWORD);

        String token = Application.getInstance(TenantUserService.class)
                .issuePasswordResetToken(TenantTestUtils.defaultTenant(), email)
                .orElseThrow()
                .token();

        TestResponse reset = resetRequest(token, NEW_PASSWORD);
        assertThat(reset.getStatusCode(), equalTo(StatusCodes.OK));

        assertThat(login(username, OLD_PASSWORD).getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat(login(username, NEW_PASSWORD).getStatusCode(), equalTo(StatusCodes.OK));

        // The token is single-use: replaying it fails.
        assertThat(resetRequest(token, "another-secret-password-1").getStatusCode(),
                equalTo(StatusCodes.BAD_REQUEST));
    }

    @Test
    void forgotIsUniformAndResetRejectedWhenDisabled() {
        setFlags(false, null, null);

        // Disabled, unknown tenant, unknown account: always the same answer, no enumeration.
        assertThat(forgot("default", "whoever@example.com").getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(forgot("does-not-exist", "whoever@example.com").getStatusCode(), equalTo(StatusCodes.OK));

        // With the feature off, reset is rejected regardless of the token.
        assertThat(resetRequest("any-token", NEW_PASSWORD).getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
    }

    @Test
    void resetRejectsUnknownToken() {
        setFlags(true, null, null);
        assertThat(resetRequest("not-a-real-token", NEW_PASSWORD).getStatusCode(),
                equalTo(StatusCodes.BAD_REQUEST));
    }

    @Test
    void emailVerificationConfirmIsSingleUseAndGatedByToggle() {
        setFlags(null, true, null);
        String username = "verify-" + CommonUtils.uuidV7();
        String email = username + "@example.com";
        Application.getInstance(UserService.class).createUser(username, email, OLD_PASSWORD);

        String token = Application.getInstance(TenantUserService.class)
                .issueEmailVerificationToken(TenantTestUtils.defaultTenant(), email)
                .orElseThrow()
                .token();

        assertThat(confirm(token).getStatusCode(), equalTo(StatusCodes.OK));
        // Single-use: the same token cannot be confirmed twice.
        assertThat(confirm(token).getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));

        // With verification disabled, confirm is rejected.
        setFlags(null, false, null);
        String other = Application.getInstance(TenantUserService.class)
                .issueEmailVerificationToken(TenantTestUtils.defaultTenant(), email)
                .orElseThrow()
                .token();
        assertThat(confirm(other).getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
    }

    @Test
    void loginIsGatedByEmailVerificationOnlyWhenRequired() {
        setFlags(null, true, true);
        String username = "gate-" + CommonUtils.uuidV7();
        String email = username + "@example.com";
        Application.getInstance(UserService.class).createUser(username, email, OLD_PASSWORD);

        // Correct credentials, but the tenant requires a verified email and this user has none yet.
        assertThat(login(username, OLD_PASSWORD).getStatusCode(), equalTo(StatusCodes.FORBIDDEN));

        String token = Application.getInstance(TenantUserService.class)
                .issueEmailVerificationToken(TenantTestUtils.defaultTenant(), email)
                .orElseThrow()
                .token();
        assertThat(confirm(token).getStatusCode(), equalTo(StatusCodes.OK));

        assertThat(login(username, OLD_PASSWORD).getStatusCode(), equalTo(StatusCodes.OK));

        // Leave the shared default tenant clean for other tests.
        setFlags(null, false, null);
    }

    @Test
    void disablingEmailVerificationAlsoClearsTheLoginRequirement() {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        TenantService tenantService = Application.getInstance(TenantService.class);

        tenantService.update(tenant.id(), null, null, null, null, null, true, true, null, null, null);
        assertThat(tenantService.findById(tenant.id()).orElseThrow().emailVerificationRequired(), is(true));

        // Disabling verification implicitly drops the login requirement too - it can never be
        // satisfied once the verify endpoints are gated off.
        tenantService.update(tenant.id(), null, null, null, null, null, false, null, null, null, null);
        assertThat(tenantService.findById(tenant.id()).orElseThrow().emailVerificationRequired(), is(false));

        setFlags(null, false, null);
    }

    private void setFlags(Boolean passwordReset, Boolean emailVerification, Boolean emailVerificationRequired) {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        Application.getInstance(TenantService.class)
                .update(tenant.id(), null, null, null, null, passwordReset, emailVerification,
                        emailVerificationRequired, null, null, null);
    }

    private TestResponse forgot(String tenantSlug, String email) {
        return TestRequest.post("/api/auth/password/forgot")
                .withStringBody("{\"tenant\":\"" + tenantSlug + "\",\"email\":\"" + email + "\"}")
                .withContentType("application/json")
                .execute();
    }

    private TestResponse resetRequest(String token, String password) {
        return TestRequest.post("/api/auth/password/reset")
                .withStringBody("{\"tenant\":\"default\",\"token\":\"" + token + "\",\"password\":\"" + password + "\"}")
                .withContentType("application/json")
                .execute();
    }

    private TestResponse confirm(String token) {
        return TestRequest.post("/api/auth/verify/confirm")
                .withStringBody("{\"tenant\":\"default\",\"token\":\"" + token + "\"}")
                .withContentType("application/json")
                .execute();
    }

    private TestResponse login(String username, String password) {
        return TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(username, password))
                .withContentType("application/json")
                .execute();
    }
}
