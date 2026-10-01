package security;

import auth.TenantContext;
import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import models.CollectionRules;
import models.FieldDefinition;
import models.FieldOptions;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import services.UserService;
import utils.AdminTestUtils;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.util.List;

import static com.mongodb.client.model.Filters.eq;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * A cascade deletes a client-named record in another collection that ApiAuthFilter never authorized,
 * so the target collection's delete rule must be evaluated against the caller.
 */
@ExtendWith({TestRunner.class})
class CascadeDeleteAuthorizationIntegrationTest {
    private static final String PASSWORD_A = "cascade-password-aaa-1";
    private static final String PASSWORD_B = "cascade-password-bbb-2";
    private static final String USER_A = "cascade-user-a";
    private static final String USER_B = "cascade-user-b";

    @Test
    void doesNotDeleteATargetTheCallerMayNotDelete() {
        String targets = seedTargets("owner");
        String holders = seedHolders(targets);

        String foreignTarget = seedTarget(targets, userId(USER_B, PASSWORD_B));
        String token = login(USER_A, PASSWORD_A);
        String holder = createHolder(holders, foreignTarget, token);

        assertThat("the holding record is the caller's own and must be deletable",
                isDenied(delete(holders, holder, token)), is(false));

        assertThat("the cascade must not delete a record of another user - its delete rule is \"owner\"",
                findById(targets, foreignTarget), notNullValue());
    }

    @Test
    void deletesATargetTheCallerOwns() {
        String targets = seedTargets("owner");
        String holders = seedHolders(targets);

        String ownTarget = seedTarget(targets, userId(USER_A, PASSWORD_A));
        String token = login(USER_A, PASSWORD_A);
        String holder = createHolder(holders, ownTarget, token);

        assertThat(isDenied(delete(holders, holder, token)), is(false));

        assertThat("a target the caller could have deleted directly must still be cascaded",
                findById(targets, ownTarget), nullValue());
    }

    @Test
    void doesNotDeleteATargetOfALockedCollection() {
        // No delete rule: a cascade is no exception, not even for the user who owns the target.
        String targets = seedTargets(null);
        String holders = seedHolders(targets);

        String ownTarget = seedTarget(targets, userId(USER_A, PASSWORD_A));
        String token = login(USER_A, PASSWORD_A);
        String holder = createHolder(holders, ownTarget, token);

        assertThat(isDenied(delete(holders, holder, token)), is(false));

        assertThat("a locked target collection must not be deletable through a cascade",
                findById(targets, ownTarget), notNullValue());
    }

    @Test
    void stillCascadesForTheAdminUi() {
        String targets = seedTargets("owner");
        String holders = seedHolders(targets);

        String foreignTarget = seedTarget(targets, userId(USER_B, PASSWORD_B));
        String holder = createHolder(holders, foreignTarget, login(USER_A, PASSWORD_A));

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
        TestResponse response = cookies
                .apply(TestRequest.create("/api/collections/" + holders + "/" + holder, "DELETE"))
                .execute();

        assertThat(isDenied(response), is(false));
        assertThat("the admin UI operates the tenant and its cascade follows that decision",
                findById(targets, foreignTarget), nullValue());
    }

    private static String seedTargets(String deleteRule) {
        String name = "cascade_targets_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                name,
                new CollectionRules("*", "*", "*", null, deleteRule, "owner"),
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("owner", FieldType.RELATION, false, true,
                                FieldOptions.forRelation("users"))));
        return name;
    }

    private static String seedHolders(String targets) {
        String name = "cascade_holders_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                name,
                new CollectionRules("auth", "auth", "auth", "auth", "owner", "owner"),
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("target", FieldType.RELATION, false, true,
                                FieldOptions.forRelation(targets, 1, true)),
                        new FieldDefinition("owner", FieldType.RELATION, false, true,
                                FieldOptions.forRelation("users"))));
        return name;
    }

    private static String seedTarget(String collection, String ownerId) {
        String id = DbUtils.id();
        collections().dataCollection(context(), collection).insertOne(new Document()
                .append("id", id)
                .append("title", "TARGET-" + id)
                .append("owner", ownerId));
        return id;
    }

    private static String createHolder(String collection, String targetId, String token) {
        TestResponse response = TestRequest.post("/api/collections/" + collection)
                .withStringBody("{\"title\":\"holder\",\"target\":\"" + targetId + "\"}")
                .withContentType("application/json")
                .withHeader("Authorization", "Bearer " + token)
                .execute();

        Document created = collections().dataCollection(context(), collection)
                .find(eq("target", targetId))
                .first();

        assertThat("the holder must be created: " + response.getContent(), created, notNullValue());
        return created.getString("id");
    }

    private static TestResponse delete(String collection, String id, String token) {
        return TestRequest.create("/api/collections/" + collection + "/" + id, "DELETE")
                .withHeader("Authorization", "Bearer " + token)
                .execute();
    }

    private static boolean isDenied(TestResponse response) {
        int status = response.getStatusCode();
        return status == 401 || status == 403 || status == 404;
    }

    private static Document findById(String collection, String id) {
        return collections().dataCollection(context(), collection).find(eq("id", id)).first();
    }

    private static TenantCollectionService collections() {
        return Application.getInstance(TenantCollectionService.class);
    }

    private static TenantContext context() {
        return TenantTestUtils.defaultTenantContext();
    }

    private static String userId(String username, String password) {
        Document existing = collections().dataCollection(context(), "users")
                .find(eq("username", username))
                .first();
        if (existing != null) {
            return existing.getString("id");
        }
        return String.valueOf(Application.getInstance(UserService.class)
                .createUser(username, null, password)
                .get("id"));
    }

    private static String login(String username, String password) {
        userId(username, password);
        TestResponse response = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(username, password))
                .withContentType("application/json")
                .execute();

        String marker = "\"accessToken\":\"";
        int start = response.getContent().indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("Login failed for " + username + ": " + response.getContent());
        }
        start += marker.length();
        return response.getContent().substring(start, response.getContent().indexOf('"', start));
    }
}
