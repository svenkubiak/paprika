package controllers;

import auth.TenantContext;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionDefinition;
import models.CollectionRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import services.UserService;
import utils.TenantTestUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * The {@code peers} preset on the users collection: an application can show the names of the
 * people a user shares a group with - and nothing beyond that. Without it, {@code users} offers
 * only {@code owner} ("my own account") or {@code auth} ("every account of the tenant").
 */
@ExtendWith({TestRunner.class})
class UsersPeersRulesIntegrationTest {
    private static final String MEMBERSHIPS = "grp_memberships";
    private static final String PASSWORD = "secret-password-123";

    @Test
    void aUserSeesTeammatesAndAlwaysTheirOwnRecordButNoStrangers() {
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        CollectionDefinition original = collections.findDefinition(ctx, "users");

        TenantTestUtils.seedCollection(
                MEMBERSHIPS,
                CollectionRules.locked(),
                CollectionGroupRulesIntegrationTest.membershipFields());

        UserService userService = Application.getInstance(UserService.class);
        String mate = CollectionGroupRulesIntegrationTest.id(
                userService.createUser("peers-mate", null, PASSWORD));
        String stranger = CollectionGroupRulesIntegrationTest.id(
                userService.createUser("peers-stranger", null, PASSWORD));
        String me = CollectionGroupRulesIntegrationTest.id(
                userService.createUser("peers-me", null, PASSWORD));
        String loner = CollectionGroupRulesIntegrationTest.id(
                userService.createUser("peers-loner", null, PASSWORD));

        CollectionGroupRulesIntegrationTest.addMembership(me, "peers-crew");
        CollectionGroupRulesIntegrationTest.addMembership(mate, "peers-crew");
        CollectionGroupRulesIntegrationTest.addMembership(stranger, "other-crew");

        replaceRules(collections, ctx, original, peersRules());
        try {
            String token = CollectionGroupRulesIntegrationTest.login("peers-me");

            TestResponse list = TestRequest.get("/api/collections/users?offset=0&limit=100")
                    .withHeader("Authorization", "Bearer " + token)
                    .execute();
            assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(list.getContent(), containsString("peers-mate"));
            assertThat(list.getContent(), containsString("peers-me"));
            assertThat(list.getContent(), not(containsString("peers-stranger")));

            TestResponse mateView = TestRequest.get("/api/collections/users/" + mate)
                    .withHeader("Authorization", "Bearer " + token)
                    .execute();
            assertThat(mateView.getStatusCode(), equalTo(StatusCodes.OK));

            TestResponse strangerView = TestRequest.get("/api/collections/users/" + stranger)
                    .withHeader("Authorization", "Bearer " + token)
                    .execute();
            assertThat(strangerView.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
            assertThat(strangerView.getContent(), not(containsString("peers-stranger")));

            // Without any membership a user still reaches exactly one record: their own.
            String lonerToken = CollectionGroupRulesIntegrationTest.login("peers-loner");
            TestResponse lonerList = TestRequest.get("/api/collections/users?offset=0&limit=100")
                    .withHeader("Authorization", "Bearer " + lonerToken)
                    .execute();
            assertThat(lonerList.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(lonerList.getContent(), containsString("\"total\":1"));
            assertThat(lonerList.getContent(), containsString("peers-loner"));

            TestResponse lonerOwn = TestRequest.get("/api/collections/users/" + loner)
                    .withHeader("Authorization", "Bearer " + lonerToken)
                    .execute();
            assertThat(lonerOwn.getStatusCode(), equalTo(StatusCodes.OK));

            // Create can never be satisfied: there is no record yet whose identity could match.
            TestResponse create = TestRequest.post("/api/collections/users")
                    .withHeader("Authorization", "Bearer " + token)
                    .withStringBody("{\"username\":\"peers-created\",\"password\":\"another-secret-123\"}")
                    .withContentType("application/json")
                    .execute();
            assertThat(create.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));

            TestResponse guest = TestRequest.get("/api/collections/users?offset=0&limit=100").execute();
            assertThat(guest.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
            assertThat(guest.getContent(), not(containsString("peers-me")));
        } finally {
            collections.replaceDefinition(ctx, original);
        }
    }

    private static CollectionRules peersRules() {
        return new CollectionRules(
                "peers", "peers", "peers", "peers", "peers",
                "owner", MEMBERSHIPS, "user", "crew", null);
    }

    private static void replaceRules(
            TenantCollectionService collections,
            TenantContext ctx,
            CollectionDefinition original,
            CollectionRules rules) {

        collections.replaceDefinition(ctx, new CollectionDefinition(
                original.id(),
                original.name(),
                original.fields(),
                original.indexes(),
                rules,
                original.system()));
    }
}
