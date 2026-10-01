package controllers;

import auth.TenantContext;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionDefinition;
import models.CollectionRules;
import models.TenantDefinition;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import services.TenantUserService;
import services.UserService;
import utils.AdminTestUtils;
import utils.DbUtils;
import utils.TenantTestUtils;
import utils.UserRecordUtils;

import static com.mongodb.client.model.Filters.eq;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * The update rule must not decide who may replace password and email: with {@code peers} or
 * {@code auth} any teammate could otherwise take another user's account over.
 */
@ExtendWith({TestRunner.class})
class UsersCredentialChangeIntegrationTest {
    private static final String MEMBERSHIPS = "grp_memberships";
    private static final String PASSWORD = "secret-password-123";
    private static final String NEW_PASSWORD = "brand-new-password-456";

    @Test
    void aTeammateCannotReplaceAnotherUsersPasswordOrEmail() {
        String crew = "cred-crew-" + DbUtils.id();
        String me = createUser("cred-me-");
        String mateName = "cred-mate-" + DbUtils.id();
        String mate = createUser(mateName, mateName + "@example.com");
        CollectionGroupRulesIntegrationTest.addMembership(me, crew);
        CollectionGroupRulesIntegrationTest.addMembership(mate, crew);

        withUsersRules(peersRules(), () -> {
            String token = loginToken(username(me));

            assertThat(patch(mate, token, "{\"password\":\"" + NEW_PASSWORD + "\"}").getStatusCode(),
                    equalTo(StatusCodes.FORBIDDEN));
            assertThat(patch(mate, token, "{\"email\":\"attacker@example.com\"}").getStatusCode(),
                    equalTo(StatusCodes.FORBIDDEN));
            // Knowing one's own password does not help with somebody else's record
            assertThat(patch(mate, token, "{\"password\":\"" + NEW_PASSWORD + "\",\"oldPassword\":\"" + PASSWORD + "\"}")
                    .getStatusCode(), equalTo(StatusCodes.FORBIDDEN));

            assertThat(login(mateName, PASSWORD).getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(login(mateName, NEW_PASSWORD).getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
            assertThat(stored(mate).getString("email"), equalTo(mateName + "@example.com"));

            // Writing back the unchanged address is not a credential change
            assertThat(patch(mate, token, "{\"email\":\"" + mateName + "@example.com\"}").getStatusCode(),
                    equalTo(StatusCodes.OK));
        });
    }

    @Test
    void anAuthUpdateRuleDoesNotOpenOtherUsersCredentialsEither() {
        String me = createUser("cred-auth-me-");
        String strangerName = "cred-auth-stranger-" + DbUtils.id();
        String stranger = createUser(strangerName, null);

        withUsersRules(new CollectionRules("auth", "auth", "", "auth", "owner", "owner"), () -> {
            String token = loginToken(username(me));

            assertThat(patch(stranger, token, "{\"password\":\"" + NEW_PASSWORD + "\"}").getStatusCode(),
                    equalTo(StatusCodes.FORBIDDEN));
            assertThat(login(strangerName, PASSWORD).getStatusCode(), equalTo(StatusCodes.OK));
        });
    }

    @Test
    void changingOwnCredentialsRequiresTheCurrentPassword() {
        String me = createUser("cred-self-");
        String name = username(me);

        withUsersRules(ownerRules(), () -> {
            String token = loginToken(name);

            assertThat(patch(me, token, "{\"password\":\"" + NEW_PASSWORD + "\"}").getStatusCode(),
                    equalTo(StatusCodes.BAD_REQUEST));
            assertThat(patch(me, token, "{\"email\":\"" + name + "-new@example.com\"}").getStatusCode(),
                    equalTo(StatusCodes.BAD_REQUEST));
            assertThat(patch(me, token,
                    "{\"password\":\"" + NEW_PASSWORD + "\",\"oldPassword\":\"wrong-password-000000\"}").getStatusCode(),
                    equalTo(StatusCodes.BAD_REQUEST));
            assertThat(login(name, PASSWORD).getStatusCode(), equalTo(StatusCodes.OK));

            TestResponse changed = patch(me, token,
                    "{\"password\":\"" + NEW_PASSWORD + "\",\"oldPassword\":\"" + PASSWORD + "\"}");
            assertThat(changed.getContent(), changed.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(changed.getContent(), not(containsString("oldPassword")));
            assertThat(stored(me).containsKey("oldPassword"), is(false));

            assertThat(login(name, NEW_PASSWORD).getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(login(name, PASSWORD).getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        });
    }

    @Test
    void aNewEmailIsUnverifiedAndPendingTokensStopWorking() {
        String name = "cred-email-" + DbUtils.id();
        String me = createUser(name, name + "@example.com");
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        TenantUserService users = Application.getInstance(TenantUserService.class);
        String first = users.issueEmailVerificationToken(tenant, name + "@example.com").orElseThrow().token();
        assertThat(users.confirmEmailVerification(tenant, first), is(true));
        String pendingVerify = users.issueEmailVerificationToken(tenant, name + "@example.com").orElseThrow().token();
        String pendingReset = users.issuePasswordResetToken(tenant, name + "@example.com").orElseThrow().token();

        withUsersRules(ownerRules(), () -> {
            String token = loginToken(name);
            TestResponse changed = patch(me, token,
                    "{\"email\":\"" + name + "-new@example.com\",\"oldPassword\":\"" + PASSWORD + "\"}");
            assertThat(changed.getContent(), changed.getStatusCode(), equalTo(StatusCodes.OK));
        });

        Document stored = stored(me);
        assertThat(stored.getString("email"), equalTo(name + "-new@example.com"));
        assertThat(stored.getBoolean(UserRecordUtils.EMAIL_VERIFIED), is(false));
        assertThat("a token mailed to the old address must not verify the new one",
                users.confirmEmailVerification(tenant, pendingVerify), is(false));
        assertThat(users.resetPassword(tenant, pendingReset, NEW_PASSWORD), is(false));
    }

    @Test
    void aRuleBypassingCallerMaySetCredentialsWithoutTheOldPassword() {
        String me = createUser("cred-admin-me-");
        String targetName = "cred-admin-target-" + DbUtils.id();
        String target = createUser(targetName, targetName + "@example.com");
        markVerified(target);
        String key = createBypassKey(me);

        withUsersRules(ownerRules(), () -> {
            TestResponse changed = patch(target, key,
                    "{\"password\":\"" + NEW_PASSWORD + "\",\"email\":\"" + targetName + "-new@example.com\"}");
            assertThat(changed.getContent(), changed.getStatusCode(), equalTo(StatusCodes.OK));
        });

        assertThat(login(targetName, NEW_PASSWORD).getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(stored(target).getBoolean(UserRecordUtils.EMAIL_VERIFIED), is(false));
    }

    @Test
    void theAdminEditorAlsoResetsVerificationOnANewEmail() {
        String name = "cred-editor-" + DbUtils.id();
        String id = createUser(name, name + "@example.com");
        markVerified(id);
        TenantUserService users = Application.getInstance(TenantUserService.class);
        TenantDefinition tenant = TenantTestUtils.defaultTenant();

        users.updateUser(tenant, id, null, name + "@example.com", null);
        assertThat("an unchanged address stays verified", stored(id).getBoolean(UserRecordUtils.EMAIL_VERIFIED), is(true));

        users.updateUser(tenant, id, null, name + "-new@example.com", null);
        assertThat(stored(id).getBoolean(UserRecordUtils.EMAIL_VERIFIED), is(false));
    }

    private static CollectionRules peersRules() {
        return new CollectionRules(
                "peers", "peers", "", "peers", "owner",
                "owner", MEMBERSHIPS, "user", "crew", null);
    }

    private static CollectionRules ownerRules() {
        return new CollectionRules("owner", "owner", "", "owner", "owner", "owner");
    }

    private static void withUsersRules(CollectionRules rules, Runnable body) {
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        TenantTestUtils.seedCollection(
                MEMBERSHIPS,
                CollectionRules.locked(),
                CollectionGroupRulesIntegrationTest.membershipFields());
        CollectionDefinition original = collections.findDefinition(ctx, "users");
        collections.replaceDefinition(ctx, new CollectionDefinition(
                original.id(),
                original.name(),
                original.fields(),
                original.indexes(),
                rules,
                original.system()));
        try {
            body.run();
        } finally {
            collections.replaceDefinition(ctx, original);
        }
    }

    private static String createUser(String prefix) {
        return createUser(prefix + DbUtils.id(), null);
    }

    private static String createUser(String username, String email) {
        return CollectionGroupRulesIntegrationTest.id(
                Application.getInstance(UserService.class).createUser(username, email, PASSWORD));
    }

    private static void markVerified(String id) {
        Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), "users")
                .updateOne(eq("id", id), new Document("$set", new Document(UserRecordUtils.EMAIL_VERIFIED, true)));
    }

    private static Document stored(String id) {
        return Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), "users")
                .find(eq("id", id))
                .first();
    }

    private static String username(String id) {
        return stored(id).getString("username");
    }

    private static TestResponse patch(String id, String bearer, String body) {
        return TestRequest.patch("/api/collections/users/" + id)
                .withHeader("Authorization", "Bearer " + bearer)
                .withStringBody(body)
                .withContentType("application/json")
                .execute();
    }

    private static TestResponse login(String username, String password) {
        return TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("default", username, password))
                .withContentType("application/json")
                .execute();
    }

    private static String loginToken(String username) {
        TestResponse response = login(username, PASSWORD);
        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        return CollectionGroupRulesIntegrationTest.extractJsonString(response.getContent(), "accessToken");
    }

    private static String createBypassKey(String userId) {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        TestResponse response = AdminTestUtils.postWithAdminCookies(
                "/api/meta/tenants/" + tenant.id() + "/api-keys",
                new AdminTestUtils.AdminCookies(AdminTestUtils.loginAsAdmin(), null),
                "{\"name\":\"cred-bypass\",\"userId\":\"" + userId + "\",\"bypassRules\":true}",
                "application/json");
        assertThat(response.getStatusCode(), equalTo(StatusCodes.CREATED));
        return CollectionGroupRulesIntegrationTest.extractJsonString(response.getContent(), "key");
    }
}
