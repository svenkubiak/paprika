package controllers;

import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionRules;
import models.FieldDefinition;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import services.UserService;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * The {@code group} preset on the membership collection itself: {@code groupCollection} is this
 * very collection, so the caller's own membership records name the groups and every membership
 * record of those groups is visible. That is how an application shows the <em>member list</em> of
 * a group without an external service.
 * <p>
 * The lookup is not circular: the resolver reads the memberships once, past the rules, and the
 * result only scopes the query that follows.
 */
@ExtendWith({TestRunner.class})
class CollectionSelfMembershipRulesIntegrationTest {
    private static final String MEMBERSHIPS = "selfmem_members";
    private static final String CREW_A = "selfmem-crew-a";
    private static final String CREW_B = "selfmem-crew-b";
    private static final String PASSWORD = "secret-password-123";

    private static String membershipA;
    private static String membershipB;
    private static String membershipPeer;
    private static String tokenA;
    private static String tokenB;
    private static String tokenC;

    @BeforeAll
    static void seed() {
        TenantTestUtils.seedCollection(MEMBERSHIPS, selfMembershipRules(), membershipFields());

        UserService userService = Application.getInstance(UserService.class);
        String userA = CollectionGroupRulesIntegrationTest.id(
                userService.createUser("selfmem-user-a", null, PASSWORD));
        String userB = CollectionGroupRulesIntegrationTest.id(
                userService.createUser("selfmem-user-b", null, PASSWORD));
        CollectionGroupRulesIntegrationTest.id(userService.createUser("selfmem-user-c", null, PASSWORD));
        String peer = CollectionGroupRulesIntegrationTest.id(
                userService.createUser("selfmem-peer", null, PASSWORD));

        tokenA = CollectionGroupRulesIntegrationTest.login("selfmem-user-a");
        tokenB = CollectionGroupRulesIntegrationTest.login("selfmem-user-b");
        tokenC = CollectionGroupRulesIntegrationTest.login("selfmem-user-c");

        membershipA = addMembership(userA, CREW_A, "membership of a");
        membershipB = addMembership(userB, CREW_B, "membership of b");
        membershipPeer = addMembership(peer, CREW_A, "membership of the peer");
    }

    /** The point of the setup: the member list of the caller's own group, not only their own row. */
    @Test
    void listReturnsEveryMembershipOfTheCallersGroups() {
        TestResponse listA = list(tokenA);
        assertThat(listA.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(listA.getContent(), containsString("membership of a"));
        assertThat(listA.getContent(), containsString("membership of the peer"));
        assertThat(listA.getContent(), not(containsString("membership of b")));

        TestResponse listB = list(tokenB);
        assertThat(listB.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(listB.getContent(), containsString("membership of b"));
        assertThat(listB.getContent(), not(containsString("membership of a")));
        assertThat(listB.getContent(), not(containsString("membership of the peer")));
    }

    @Test
    void aCallerWithoutAnyMembershipSeesNothing() {
        TestResponse list = list(tokenC);

        assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(list.getContent(), containsString("\"total\":0"));
        assertThat(list.getContent(), not(containsString("membership of")));
    }

    @Test
    void viewReachesTheMembershipsOfTheOwnGroupAndNoOther() {
        assertThat(view(tokenA, membershipA).getStatusCode(), equalTo(StatusCodes.OK));

        TestResponse peer = view(tokenA, membershipPeer);
        assertThat(peer.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(peer.getContent(), containsString("membership of the peer"));

        TestResponse foreign = view(tokenA, membershipB);
        assertThat(foreign.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
        assertThat(foreign.getContent(), not(containsString("membership of b")));

        assertThat(view(tokenC, membershipA).getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
    }

    private static TestResponse list(String token) {
        return TestRequest.get("/api/collections/" + MEMBERSHIPS + "?offset=0&limit=50")
                .withHeader("Authorization", "Bearer " + token)
                .execute();
    }

    private static TestResponse view(String token, String recordId) {
        return TestRequest.get("/api/collections/" + MEMBERSHIPS + "/" + recordId)
                .withHeader("Authorization", "Bearer " + token)
                .execute();
    }

    /**
     * The collection scopes itself: groupCollection is MEMBERSHIPS, and the group of a membership
     * record is the same field the resolver reads. Writes stay locked - who joins a group is the
     * application's decision, not this test's subject.
     */
    static CollectionRules selfMembershipRules() {
        return new CollectionRules(
                "group", "group", null, null, null,
                "owner", MEMBERSHIPS, "user", "crew", "crew");
    }

    static List<FieldDefinition> membershipFields() {
        return List.of(
                new FieldDefinition("user", FieldType.STRING, true, false, null),
                new FieldDefinition("crew", FieldType.STRING, true, false, null),
                new FieldDefinition("note", FieldType.STRING, false, false, null));
    }

    private static String addMembership(String userId, String group, String note) {
        String id = DbUtils.id();
        Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), MEMBERSHIPS)
                .insertOne(new Document()
                        .append("id", id)
                        .append("user", userId)
                        .append("crew", group)
                        .append("note", note));
        return id;
    }
}
