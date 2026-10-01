package services;

import helpers.AdminLoginResponseHelper;
import helpers.AdminSettingsResponseHelper;
import io.mangoo.core.Application;
import io.mangoo.exceptions.MangooHashingException;
import io.mangoo.routing.Response;
import io.mangoo.test.TestRunner;
import io.undertow.util.HttpString;
import models.TenantDefinition;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import results.AdminLoginResult;
import results.AdminSettingsResult;
import results.SuperadminPasswordResult;
import results.TenantLoginResult;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * A hashing capacity refusal must not read as a credential error. Provoked by overriding the hashing,
 * because mangoo's fair semaphore and shared timeout make saturating the real limit unreliable.
 */
@ExtendWith({TestRunner.class})
class PasswordHashOverloadTest {
    private static final String USERNAME = "overload-user-" + utils.DbUtils.id();
    private static final String PASSWORD = "overload-password-123";

    private static TenantDefinition tenant;

    @BeforeAll
    static void seed() {
        TenantService tenantService = Application.getInstance(TenantService.class);
        tenant = tenantService.findBySlug("overload-tenant")
                .orElseGet(() -> tenantService.create("Overload Tenant", "overload-tenant"));

        Application.getInstance(TenantUserService.class).createUser(tenant, USERNAME, null, PASSWORD);
    }

    @Test
    void aTenantLoginThatCannotBeHashedIsNotReportedAsAWrongPassword() {
        TenantLoginResult result = refusingTenantUsers()
                .authenticateForLogin(USERNAME, PASSWORD, tenant.slug());

        assertThat("an overload must not answer as a credential error",
                result.status(), equalTo(TenantLoginResult.Status.AT_CAPACITY));
    }

    /** The unknown-username branch hashes too; refusing only that one would reveal account existence. */
    @Test
    void theRefusalIsTheSameForAKnownAndAnUnknownUsername() {
        TenantUserService users = refusingTenantUsers();

        TenantLoginResult known = users.authenticateForLogin(USERNAME, PASSWORD, tenant.slug());
        TenantLoginResult unknown = users.authenticateForLogin("overload-nobody", PASSWORD, tenant.slug());

        assertThat(known.status(), equalTo(TenantLoginResult.Status.AT_CAPACITY));
        assertThat("both branches hash, so both are refused alike",
                unknown.status(), equalTo(known.status()));
    }

    @Test
    void aSelfRegistrationThatCannotBeHashedIsRefused() {
        Optional<java.util.Map<String, Object>> created = refusingTenantUsers()
                .registerUser(tenant, "overload-register-" + utils.DbUtils.id(), null, PASSWORD);

        assertThat("no user may be created when its password could not be hashed",
                created, equalTo(Optional.empty()));
    }

    @Test
    void aSuperadminVerificationThatCannotBeHashedIsNotReportedAsAWrongPassword() {
        String password = utils.AdminTestUtils.prepareAdminPassword();

        SuperadminPasswordResult result = refusingSystemUsers().verifyPassword("admin", password);

        assertThat(result.isAtCapacity(), equalTo(true));
        assertThat("a refusal is not a sign-in", result.auth(), equalTo(Optional.empty()));
    }

    @Test
    void theTenantLoginRefusalBecomesA429WithARetryAfter() {
        Response response = Application.getInstance(AuthResponseService.class)
                .toLoginResponse(TenantLoginResult.atCapacity());

        assertThat(response.getStatusCode(), equalTo(429));
        assertThat("a refusal has to tell the client when to come back",
                response.getHeader(new HttpString("Retry-After")), notNullValue());
        assertThat(response.getBody(), containsString("Too many authentication requests"));
    }

    @Test
    void theSuperadminLoginRefusalBecomesA429WithARetryAfter() {
        Response response = AdminLoginResponseHelper.toJsonResponse(AdminLoginResult.atCapacity());

        assertThat(response.getStatusCode(), equalTo(429));
        assertThat(response.getHeader(new HttpString("Retry-After")), notNullValue());
        assertThat(response.getBody(), containsString("Too many authentication requests"));
    }

    @Test
    void theProfileRefusalBecomesA429WithARetryAfter() {
        Response response = AdminSettingsResponseHelper.toResponse(AdminSettingsResult.atCapacity());

        assertThat(response.getStatusCode(), equalTo(429));
        assertThat(response.getHeader(new HttpString("Retry-After")), notNullValue());
        assertThat(response.getBody(), containsString("Too many authentication requests"));
    }

    private static TenantUserService refusingTenantUsers() {
        return new TenantUserService(
                Application.getInstance(TenantDatabaseResolver.class),
                Application.getInstance(TenantService.class),
                Application.getInstance(RealtimeService.class),
                Application.getInstance(TenantCollectionService.class),
                Application.getInstance(ValidationService.class),
                Application.getInstance(TokenVersionService.class)) {
            @Override
            boolean matchesPassword(String password, Document user) {
                throw refusal();
            }

            @Override
            void burnPasswordHashTime(String password) {
                throw refusal();
            }

            @Override
            String hashPassword(String password, String salt) {
                throw refusal();
            }
        };
    }

    private static SystemUserService refusingSystemUsers() {
        return new SystemUserService(Application.getInstance(TenantDatabaseResolver.class)) {
            @Override
            boolean matchesPassword(String password, Document user) {
                throw refusal();
            }
        };
    }

    private static MangooHashingException refusal() {
        return new MangooHashingException("No Argon2 hashing slot became available within 200 ms");
    }
}
