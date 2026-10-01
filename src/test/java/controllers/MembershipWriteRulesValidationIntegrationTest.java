package controllers;

import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestResponse;
import io.mangoo.utils.JsonUtils;
import io.undertow.util.StatusCodes;
import models.CollectionDefinition;
import models.CollectionRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import utils.AdminTestUtils;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * A membership record grants the group it names, so "owner", "auth" or "*" as create/update rule
 * would let users add themselves to any group ("owner" pins only the member field).
 */
@ExtendWith({TestRunner.class})
class MembershipWriteRulesValidationIntegrationTest {
    private static final List<String> SELF_JOIN_RULES = List.of("owner", "auth", "*");

    @Test
    void aGroupRuleOnASelfJoinableMembershipCollectionIsRejected() {
        for (String rule : SELF_JOIN_RULES) {
            String createOpen = membershipCollection(new CollectionRules("", "", rule, "", "owner", "user"));
            String updateOpen = membershipCollection(new CollectionRules("", "", "", rule, "owner", "user"));

            for (String memberships : List.of(createOpen, updateOpen)) {
                String documents = "mwr_docs_" + DbUtils.id();
                TestResponse response = createDocuments(documents, memberships);

                assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
                assertThat(response.getContent(), containsString(memberships + " holds the memberships for " + documents));
                assertThat(response.getContent(), containsString("\\\"" + rule + "\\\""));
                assertThat(definition(documents), nullValue());
            }
        }
    }

    @Test
    void lockedAndGroupWriteRulesAreAccepted() {
        String locked = membershipCollection(CollectionRules.locked());
        TestResponse onLocked = createDocuments("mwr_docs_" + DbUtils.id(), locked);
        assertThat(onLocked.getContent(), onLocked.getStatusCode(), equalTo(StatusCodes.CREATED));

        // Deleting the own membership grants nothing, so the delete rule stays free
        String memberManaged = "mwr_members_" + DbUtils.id();
        TestResponse membersCreated = createMemberships(memberManaged, selfReferencing(memberManaged, "group", "group"));
        assertThat(membersCreated.getContent(), membersCreated.getStatusCode(), equalTo(StatusCodes.CREATED));

        TestResponse onGroup = createDocuments("mwr_docs_" + DbUtils.id(), memberManaged);
        assertThat(onGroup.getContent(), onGroup.getStatusCode(), equalTo(StatusCodes.CREATED));
    }

    @Test
    void aMembershipCollectionInUseCannotBeLoosenedAfterwards() {
        String memberships = membershipCollection(CollectionRules.locked());
        String documents = "mwr_docs_" + DbUtils.id();
        assertThat(createDocuments(documents, memberships).getStatusCode(), equalTo(StatusCodes.CREATED));

        TestResponse createOwner = updateMemberships(memberships, new CollectionRules("", "", "owner", "", "", "user"));
        assertThat(createOwner.getContent(), createOwner.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(createOwner.getContent(), containsString(memberships + " holds the memberships for " + documents));

        TestResponse updateAuth = updateMemberships(memberships, new CollectionRules("", "", "", "auth", "", "user"));
        assertThat(updateAuth.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));

        CollectionRules stored = definition(memberships).rulesOrDefault();
        assertThat(stored.createRule(), anyOf(nullValue(), emptyString()));
        assertThat(stored.updateRule(), anyOf(nullValue(), emptyString()));

        TestResponse leaveOwn = updateMemberships(memberships, new CollectionRules("", "", "", "", "owner", "user"));
        assertThat(leaveOwn.getContent(), leaveOwn.getStatusCode(), equalTo(StatusCodes.OK));
    }

    @Test
    void aSelfReferencingMembershipCollectionIsCheckedAgainstItsNewRules() {
        String onCreate = "mwr_self_" + DbUtils.id();
        TestResponse created = createMemberships(onCreate, selfReferencing(onCreate, "owner", ""));
        assertThat(created.getContent(), created.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(created.getContent(), containsString(onCreate + " holds the memberships for " + onCreate));
        assertThat(definition(onCreate), nullValue());

        String onUpdate = membershipCollection(new CollectionRules("", "", "owner", "", "", "user"));
        TestResponse updated = updateMemberships(onUpdate, selfReferencing(onUpdate, "owner", "owner"));
        assertThat(updated.getContent(), updated.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(definition(onUpdate).rulesOrDefault().groupCollection(), nullValue());
    }

    @Test
    void aSchemaImportCannotIntroduceTheCombinationEither() {
        String memberships = "mwr_import_members_" + DbUtils.id();
        String documents = "mwr_import_docs_" + DbUtils.id();
        String schema = """
                {"version":"1","collections":[
                  {"name":"%s","fields":[
                     {"name":"title","type":"STRING","required":true},
                     {"name":"crew","type":"STRING","required":true}],
                   "indexes":[],"rules":%s},
                  {"name":"%s","fields":[
                     {"name":"user","type":"STRING","required":true},
                     {"name":"crew","type":"STRING","required":true}],
                   "indexes":[],"rules":{"createRule":"owner","ownerField":"user"}}
                ],"hooks":[]}
                """.formatted(documents, JsonUtils.toJson(documentRules(memberships)), memberships);

        TestResponse response = importSchema(schema);
        assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString(memberships + " holds the memberships for " + documents));
        assertThat(definition(documents), nullValue());
        assertThat(definition(memberships), nullValue());

        // Loosening a stored membership collection that a stored collection already relies on
        String stored = membershipCollection(CollectionRules.locked());
        String storedDocuments = "mwr_import_docs_" + DbUtils.id();
        assertThat(createDocuments(storedDocuments, stored).getStatusCode(), equalTo(StatusCodes.CREATED));

        String loosening = """
                {"version":"1","collections":[
                  {"name":"%s","fields":[
                     {"name":"user","type":"STRING","required":true},
                     {"name":"crew","type":"STRING","required":true}],
                   "indexes":[],"rules":{"updateRule":"auth","ownerField":"user"}}
                ],"hooks":[]}
                """.formatted(stored);

        TestResponse loosened = importSchema(loosening);
        assertThat(loosened.getContent(), loosened.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(loosened.getContent(), containsString(stored + " holds the memberships for " + storedDocuments));
        assertThat(definition(stored).rulesOrDefault().updateRule(), anyOf(nullValue(), emptyString()));
    }

    private static CollectionRules documentRules(String memberships) {
        return new CollectionRules(
                "group", "group", "group", "group", "group",
                "owner", memberships, "user", "crew", "crew");
    }

    private static CollectionRules selfReferencing(String collection, String createRule, String updateRule) {
        return new CollectionRules(
                "group", "group", createRule, updateRule, "owner",
                "user", collection, "user", "crew", "crew");
    }

    private static String membershipCollection(CollectionRules rules) {
        String name = "mwr_members_" + DbUtils.id();
        TestResponse response = createMemberships(name, rules);
        assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.CREATED));
        return name;
    }

    private static TestResponse createMemberships(String name, CollectionRules rules) {
        return AdminTestUtils.postWithAdminCookies(
                "/api/meta/collections/" + name,
                AdminTestUtils.loginAsAdminWithDefaultTenant(),
                membershipBody(name, rules),
                "application/json");
    }

    private static TestResponse updateMemberships(String name, CollectionRules rules) {
        return AdminTestUtils.patchWithAdminCookies(
                "/api/meta/collections/" + name + "/" + definition(name).id(),
                AdminTestUtils.loginAsAdminWithDefaultTenant(),
                membershipBody(name, rules),
                "application/json");
    }

    private static String membershipBody(String name, CollectionRules rules) {
        return """
                {"name":"%s",
                 "fields":[{"name":"user","type":"STRING","required":true},
                           {"name":"crew","type":"STRING","required":true}],
                 "indexes":[],
                 "rules":%s}
                """.formatted(name, JsonUtils.toJson(rules));
    }

    private static TestResponse createDocuments(String name, String memberships) {
        String body = """
                {"name":"%s",
                 "fields":[{"name":"title","type":"STRING","required":true},
                           {"name":"crew","type":"STRING","required":true}],
                 "indexes":[],
                 "rules":%s}
                """.formatted(name, JsonUtils.toJson(documentRules(memberships)));

        return AdminTestUtils.postWithAdminCookies(
                "/api/meta/collections/" + name,
                AdminTestUtils.loginAsAdminWithDefaultTenant(),
                body,
                "application/json");
    }

    private static TestResponse importSchema(String schema) {
        return AdminTestUtils.postWithAdminCookies(
                "/api/meta/schema/import",
                AdminTestUtils.loginAsAdminWithDefaultTenant(),
                schema,
                "application/json");
    }

    private static CollectionDefinition definition(String name) {
        return Application.getInstance(TenantCollectionService.class)
                .findDefinition(TenantTestUtils.defaultTenantContext(), name);
    }
}
