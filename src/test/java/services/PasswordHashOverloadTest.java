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
 * Executable specification of what an instance answers when it cannot hash right now.
 * <p>
 * mangoo caps how many Argon2id computations run at once and rejects with a
 * {@link MangooHashingException} once a caller waited longer than
 * {@code authentication.hashing.timeout}. That is a statement about the server, not about the
 * credentials that were named, and the whole point of the separate {@code AT_CAPACITY} outcome is
 * that it does not read as a credential error: answering 401 would tell the rightful owner their
 * password is wrong and would tell an attacker their guess failed when it was never checked.
 * <p>
 * The refusal is provoked by overriding the hashing on the service rather than by saturating the
 * real limit. Saturating it from out here cannot be made reliable: the semaphore is fair and every
 * caller shares one timeout, so the callers queued ahead of a login give up before the login's own
 * deadline and hand it the slot instead of starving it.
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

    /**
     * The unknown-username branch hashes too, so that the response time does not give account
     * existence away. It therefore has to be refused exactly like the known one - a refusal that
     * only happened for missing users would hand out the very thing that branch exists to hide.
     */
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

    // ---------------------------------------------------------------------------------------
    // The HTTP answer the outcome turns into
    // ---------------------------------------------------------------------------------------

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

    // ---------------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------------

    /** The real service, with every path that would hash refusing the way mangoo refuses. */
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
