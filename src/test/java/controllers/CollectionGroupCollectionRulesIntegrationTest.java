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

import static com.mongodb.client.model.Filters.eq;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * The {@code group} preset on the group collection itself: {@code groupRecordField} is
 * {@code "id"}, so the record <em>is</em> the group and every member of it reaches it. No second
 * field duplicating the record's own id is involved.
 * <p>
 * Create is deliberately a different preset here - a "group" create rule could never be satisfied
 * and is refused while the collection is saved (see MetaMembershipRulesValidationIntegrationTest).
 */
@ExtendWith({TestRunner.class})
class CollectionGroupCollectionRulesIntegrationTest {
    private static final String MEMBERSHIPS = "grpself_memberships";
    private static final String TEAMS = "grpself_teams";
    private static final String PASSWORD = "secret-password-123";

    private static String teamA;
    private static String teamB;
    private static String tokenA;
    private static String tokenB;
    private static String tokenC;
    private static String tokenMember;

    @BeforeAll
    static void seed() {
        TenantTestUtils.seedCollection(MEMBERSHIPS, CollectionRules.locked(), membershipFields());
        TenantTestUtils.seedCollection(TEAMS, groupCollectionRules(), teamFields());

        UserService userService = Application.getInstance(UserService.class);
        String userA = CollectionGroupRulesIntegrationTest.id(
                userService.createUser("grpself-user-a", null, PASSWORD));
        String userB = CollectionGroupRulesIntegrationTest.id(
                userService.createUser("grpself-user-b", null, PASSWORD));
        CollectionGroupRulesIntegrationTest.id(userService.createUser("grpself-user-c", null, PASSWORD));
        String member = CollectionGroupRulesIntegrationTest.id(
                userService.createUser("grpself-member", null, PASSWORD));

        tokenA = CollectionGroupRulesIntegrationTest.login("grpself-user-a");
        tokenB = CollectionGroupRulesIntegrationTest.login("grpself-user-b");
        tokenC = CollectionGroupRulesIntegrationTest.login("grpself-user-c");
        tokenMember = CollectionGroupRulesIntegrationTest.login("grpself-member");

        teamA = insertTeam("Team A", userA);
        teamB = insertTeam("Team B", userB);

        addMembership(userA, teamA);
        addMembership(userB, teamB);
        // Joined team A without having created it - the case "owner" would not cover.
        addMembership(member, teamA);
    }

    @Test
    void listReturnsOnlyTheGroupsTheCallerBelongsTo() {
        TestResponse listA = list(tokenA);
        assertThat(listA.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(listA.getContent(), containsString("Team A"));
        assertThat(listA.getContent(), not(containsString("Team B")));

        TestResponse listB = list(tokenB);
        assertThat(listB.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(listB.getContent(), containsString("Team B"));
        assertThat(listB.getContent(), not(containsString("Team A")));
    }

    /** No membership means an empty list, never the whole collection of groups. */
    @Test
    void aCallerWithoutAnyMembershipSeesNoGroup() {
        TestResponse list = list(tokenC);

        assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(list.getContent(), containsString("\"total\":0"));
        assertThat(list.getContent(), not(containsString("Team A")));
        assertThat(list.getContent(), not(containsString("Team B")));
    }

    @Test
    void viewOfAForeignGroupIsRefused() {
        TestResponse foreign = TestRequest.get("/api/collections/" + TEAMS + "/" + teamB)
                .withHeader("Authorization", "Bearer " + tokenA)
                .execute();
        assertThat(foreign.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
        assertThat(foreign.getContent(), not(containsString("Team B")));

        TestResponse own = TestRequest.get("/api/collections/" + TEAMS + "/" + teamA)
                .withHeader("Authorization", "Bearer " + tokenA)
                .execute();
        assertThat(own.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(own.getContent(), containsString("Team A"));
    }

    /**
     * Every member may edit the group, not only whoever created it. An application that wants it
     * narrower configures "owner" - that is its decision, not the preset's.
     */
    @Test
    void aMemberMayUpdateTheGroupItDidNotCreate() {
        TestResponse updated = TestRequest.patch("/api/collections/" + TEAMS + "/" + teamA)
                .withHeader("Authorization", "Bearer " + tokenMember)
                .withStringBody("{\"name\":\"Team A renamed\"}")
                .withContentType("application/json")
                .execute();
        assertThat(updated.getContent(), updated.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(stored(teamA).getString("name"), equalTo("Team A renamed"));

        TestResponse foreign = TestRequest.patch("/api/collections/" + TEAMS + "/" + teamB)
                .withHeader("Authorization", "Bearer " + tokenMember)
                .withStringBody("{\"name\":\"Hijacked\"}")
                .withContentType("application/json")
                .execute();
        assertThat(foreign.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
        assertThat(stored(teamB).getString("name"), equalTo("Team B"));

        // Put the fixture back for the tests that assert on the name.
        Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), TEAMS)
                .updateOne(eq("id", teamA), new Document("$set", new Document("name", "Team A")));
    }

    /**
     * A record cannot be relabelled as another group. The membership check refuses the body first
     * (the auth filter runs before validation); a body naming the caller's own group would then
     * still be refused by validation, because "id" is read-only on write. Either way nothing moves.
     */
    @Test
    void aGroupRecordCannotBeRelabelledAsAnotherGroup() {
        TestResponse moved = TestRequest.patch("/api/collections/" + TEAMS + "/" + teamA)
                .withHeader("Authorization", "Bearer " + tokenA)
                .withStringBody("{\"id\":\"" + teamB + "\"}")
                .withContentType("application/json")
                .execute();

        assertThat(moved.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
        assertThat(stored(teamA).getString("id"), equalTo(teamA));
        assertThat(stored(teamB).getString("id"), equalTo(teamB));
    }

    private static TestResponse list(String token) {
        return TestRequest.get("/api/collections/" + TEAMS + "?offset=0&limit=50")
                .withHeader("Authorization", "Bearer " + token)
                .execute();
    }

    private static Document stored(String recordId) {
        return Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), TEAMS)
                .find(eq("id", recordId))
                .first();
    }

    /** Create is "auth": nobody can be a member of a group that does not exist yet. */
    static CollectionRules groupCollectionRules() {
        return new CollectionRules(
                "group", "group", "auth", "group", "group",
                "owner", MEMBERSHIPS, "user", "team", "id");
    }

    static List<FieldDefinition> membershipFields() {
        return List.of(
                new FieldDefinition("user", FieldType.STRING, true, false, null),
                new FieldDefinition("team", FieldType.STRING, true, false, null));
    }

    static List<FieldDefinition> teamFields() {
        return List.of(
                new FieldDefinition("name", FieldType.STRING, true, false, null),
                new FieldDefinition("owner", FieldType.STRING, false, false, null));
    }

    private static String insertTeam(String name, String owner) {
        String id = DbUtils.id();
        Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), TEAMS)
                .insertOne(new Document()
                        .append("id", id)
                        .append("name", name)
                        .append("owner", owner));
        return id;
    }

    private static void addMembership(String userId, String team) {
        Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), MEMBERSHIPS)
                .insertOne(new Document()
                        .append("id", DbUtils.id())
                        .append("user", userId)
                        .append("team", team));
    }
}
