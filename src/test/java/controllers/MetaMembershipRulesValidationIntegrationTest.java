package controllers;

import auth.TenantContext;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionRules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import utils.AdminTestUtils;
import utils.DbUtils;
import utils.TenantTestUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/** An incomplete membership rule is refused on save rather than stored and denying every request. */
@ExtendWith({TestRunner.class})
class MetaMembershipRulesValidationIntegrationTest {
    private static final String MEMBERSHIPS = "val_memberships";

    @BeforeAll
    static void seed() {
        TenantTestUtils.seedCollection(
                MEMBERSHIPS,
                CollectionRules.locked(),
                CollectionGroupRulesIntegrationTest.membershipFields());
    }

    @Test
    void aGroupRuleWithoutConfigurationIsRejected() {
        TestResponse response = create(rules(null, null, null, null));

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("groupCollection"));
    }

    @Test
    void anUnknownMembershipCollectionIsRejected() {
        TestResponse response = create(rules("does_not_exist", "user", "crew", "crew"));

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("Unknown membership collection: does_not_exist"));
    }

    @Test
    void anUnknownFieldOfTheMembershipCollectionIsRejected() {
        TestResponse response = create(rules(MEMBERSHIPS, "nope", "crew", "crew"));

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("groupMemberField"));
        assertThat(response.getContent(), containsString("has no field"));
    }

    @Test
    void anUnknownFieldOfTheOwnCollectionIsRejected() {
        TestResponse response = create(rules(MEMBERSHIPS, "user", "crew", "not_a_field"));

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("groupRecordField"));
    }

    @Test
    void aGroupRuleWithoutTheRecordFieldIsRejected() {
        TestResponse response = create(rules(MEMBERSHIPS, "user", "crew", null));

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("groupRecordField"));
    }

    @Test
    void peersIsRefusedOutsideTheUsersCollection() {
        TestResponse response = create(new CollectionRules(
                "peers", null, null, null, null, "owner", MEMBERSHIPS, "user", "crew", null));

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("peers"));
    }

    @Test
    void anUnknownRuleValueStillNamesTheAllowedOnes() {
        TestResponse response = create(new CollectionRules(
                "teams", null, null, null, null, "owner", null, null, null, null));

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("group"));
        assertThat(response.getContent(), containsString("peers"));
    }

    @Test
    void theRecordIdIsAcceptedAsTheRecordField() {
        String collection = "val_self_" + DbUtils.id();
        TestResponse response = create(collection, new CollectionRules(
                "group", "group", "auth", "group", "group",
                "owner", MEMBERSHIPS, "user", "crew", "id"));

        assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.CREATED));

        CollectionRules stored = Application.getInstance(TenantCollectionService.class)
                .findDefinition(TenantTestUtils.defaultTenantContext(), collection)
                .rules();
        assertThat(stored.groupRecordField(), equalTo("id"));
    }

    /** Saved with a PATCH on the existing collection, as the Rules tab does. */
    @Test
    void theCollectionItselfIsAcceptedAsTheMembershipCollection() {
        String collection = "val_selfmem_" + DbUtils.id();
        assertThat(create(collection, CollectionRules.locked()).getStatusCode(), equalTo(StatusCodes.CREATED));

        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        String id = collections.findDefinition(ctx, collection).id();

        TestResponse response = update(collection, id, new CollectionRules(
                "group", "group", null, null, null,
                "owner", collection, "crew", "crew", "crew"));
        assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.OK));

        CollectionRules stored = collections.findDefinition(ctx, collection).rules();
        assertThat(stored.groupCollection(), equalTo(collection));
        assertThat(stored.groupMemberField(), equalTo("crew"));
        assertThat(stored.groupRecordField(), equalTo("crew"));
    }

    /** A create rule on the record's own id could never be satisfied. */
    @Test
    void aGroupCreateRuleOnTheRecordIdIsRejected() {
        String collection = "val_self_create_" + DbUtils.id();
        TestResponse response = create(collection, rules(MEMBERSHIPS, "user", "crew", "id"));

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("create"));
        assertThat(response.getContent(), containsString("id"));

        assertThat(Application.getInstance(TenantCollectionService.class)
                .findDefinition(TenantTestUtils.defaultTenantContext(), collection), nullValue());
    }

    @Test
    void anotherSystemFieldAsTheRecordFieldIsRejected() {
        TestResponse response = create(rules(MEMBERSHIPS, "user", "crew", "createdAt"));

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("groupRecordField"));
        assertThat(response.getContent(), containsString("createdAt"));
    }

    @Test
    void aCompleteConfigurationIsAccepted() {
        String collection = "val_ok_" + DbUtils.id();
        TestResponse response = create(collection, rules(MEMBERSHIPS, "user", "crew", "crew"));

        assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.CREATED));

        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        CollectionRules stored = Application.getInstance(TenantCollectionService.class)
                .findDefinition(ctx, collection)
                .rules();

        assertThat(stored.groupCollection(), equalTo(MEMBERSHIPS));
        assertThat(stored.groupMemberField(), equalTo("user"));
        assertThat(stored.groupField(), equalTo("crew"));
        assertThat(stored.groupRecordField(), equalTo("crew"));
    }

    @Test
    void aRejectedDefinitionIsNotStored() {
        String collection = "val_rejected_" + DbUtils.id();
        assertThat(create(collection, rules(null, null, null, null)).getStatusCode(),
                equalTo(StatusCodes.BAD_REQUEST));

        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        assertThat(Application.getInstance(TenantCollectionService.class).findDefinition(ctx, collection),
                nullValue());
    }

    private static CollectionRules rules(
            String groupCollection,
            String memberField,
            String groupField,
            String recordField) {

        return new CollectionRules(
                "group", "group", "group", "group", "group",
                "owner", groupCollection, memberField, groupField, recordField);
    }

    private static TestResponse create(CollectionRules rules) {
        return create("val_col_" + DbUtils.id(), rules);
    }

    private static TestResponse create(String collection, CollectionRules rules) {
        String body = """
                {"name":"%s",
                 "fields":[{"name":"title","type":"STRING","required":true},
                           {"name":"crew","type":"STRING","required":true}],
                 "indexes":[],
                 "rules":%s}
                """.formatted(collection, json(rules));

        return AdminTestUtils.postWithAdminCookies(
                "/api/meta/collections/" + collection,
                AdminTestUtils.loginAsAdminWithDefaultTenant(),
                body,
                "application/json");
    }

    private static TestResponse update(String collection, String id, CollectionRules rules) {
        String body = """
                {"name":"%s",
                 "fields":[{"name":"title","type":"STRING","required":true},
                           {"name":"crew","type":"STRING","required":true}],
                 "indexes":[],
                 "rules":%s}
                """.formatted(collection, json(rules));

        return AdminTestUtils.patchWithAdminCookies(
                "/api/meta/collections/" + collection + "/" + id,
                AdminTestUtils.loginAsAdminWithDefaultTenant(),
                body,
                "application/json");
    }

    private static String json(CollectionRules rules) {
        return io.mangoo.utils.JsonUtils.toJson(rules);
    }
}
