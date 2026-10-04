package controllers;

import auth.TenantContext;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionDefinition;
import models.CollectionRules;
import models.IndexDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import utils.AdminTestUtils;
import utils.DbUtils;
import utils.TenantTestUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/** An import must not produce a configuration that the meta API itself refuses. */
@ExtendWith({TestRunner.class})
class SchemaImportValidationIntegrationTest {
    private static final String IMPORT_URI = "/api/meta/schema/import";

    /** A free expression like {@code "true"} would become an empty Mongo filter open to anonymous callers. */
    @Test
    void anUnsupportedRuleIsRejectedAndNothingIsWritten() {
        String intact = "schemaval_intact_" + DbUtils.id();
        String evil = "schemaval_rule_" + DbUtils.id();

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        // The valid entry comes first: without an up-front check it would already be written.
        String schema = """
                {"version":"1","collections":[
                  {"name":"%s","fields":[{"name":"title","type":"STRING","required":true}],"indexes":[],
                   "rules":{"listRule":"*","ownerField":"owner"}},
                  {"name":"%s","fields":[{"name":"title","type":"STRING","required":true}],"indexes":[],
                   "rules":{"listRule":"true","ownerField":"owner"}}
                ],"hooks":[]}
                """.formatted(intact, evil);

        TestResponse response = AdminTestUtils.postWithAdminCookies(
                IMPORT_URI, cookies, schema, "application/json");

        assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("Unsupported rule"));

        assertThat("the rejected collection must not exist", definition(evil), nullValue());
        assertThat("an entry before the rejected one must not have been written",
                definition(intact), nullValue());
    }

    @Test
    void aReservedFieldNameIsRejected() {
        String collection = "schemaval_reserved_" + DbUtils.id();

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        String schema = """
                {"version":"1","collections":[
                  {"name":"%s","fields":[{"name":"createdAt","type":"STRING","required":true}],"indexes":[],
                   "rules":{"listRule":"*","ownerField":"owner"}}
                ],"hooks":[]}
                """.formatted(collection);

        TestResponse response = AdminTestUtils.postWithAdminCookies(
                IMPORT_URI, cookies, schema, "application/json");

        assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("Reserved field name"));
        assertThat(definition(collection), nullValue());
    }

    /**
     * The 30s cap bounds how long a blocking hook holds a request thread. The tenant's hooks are
     * dropped before re-insert, so the check must run before the first write.
     */
    @Test
    void aHookWithAnExcessiveTimeoutIsRejectedAndTheExistingHooksSurvive() {
        String collection = "schemaval_hooktimeout_" + DbUtils.id();
        TenantTestUtils.seedCollection(collection, new CollectionRules("*", "*", "*", "*", "*", "owner"));

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
        createHook(cookies, collection, "Surviving hook");

        String schema = """
                {"version":"1","collections":[],"hooks":[
                  {"name":"Endless hook","collection":"%s","event":"beforeCreate",
                   "url":"https://example.com/hook","secret":"a-signing-secret","timeoutMs":2147483647}
                ]}
                """.formatted(collection);

        TestResponse response = AdminTestUtils.postWithAdminCookies(
                IMPORT_URI, cookies, schema, "application/json");

        assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("timeout"));

        TestResponse hooks = AdminTestUtils.getWithAdminCookies(
                "/api/meta/collections/" + collection + "/hooks", cookies);
        assertThat("a rejected import must not have dropped the hooks that were there",
                hooks.getContent(), containsString("Surviving hook"));
        assertThat(hooks.getContent(), org.hamcrest.Matchers.not(containsString("Endless hook")));
    }

    @Test
    void aHookWithoutASigningSecretIsRejected() {
        String collection = "schemaval_hooksecret_" + DbUtils.id();
        TenantTestUtils.seedCollection(collection, new CollectionRules("*", "*", "*", "*", "*", "owner"));

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        String schema = """
                {"version":"1","collections":[],"hooks":[
                  {"name":"Unsigned hook","collection":"%s","event":"afterCreate",
                   "url":"https://example.com/hook"}
                ]}
                """.formatted(collection);

        TestResponse response = AdminTestUtils.postWithAdminCookies(
                IMPORT_URI, cookies, schema, "application/json");

        assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("secret"));
    }

    @Test
    void anAuthHookOutsideTheUsersCollectionIsRejected() {
        String collection = "schemaval_authhook_" + DbUtils.id();
        TenantTestUtils.seedCollection(collection, new CollectionRules("*", "*", "*", "*", "*", "owner"));

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        String schema = """
                {"version":"1","collections":[],"hooks":[
                  {"name":"Misplaced auth hook","collection":"%s","event":"afterLogin",
                   "url":"https://example.com/hook","secret":"a-signing-secret"}
                ]}
                """.formatted(collection);

        TestResponse response = AdminTestUtils.postWithAdminCookies(
                IMPORT_URI, cookies, schema, "application/json");

        assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("users collection"));
    }

    /** Duplicate usernames in a tenant are not recoverable, so an import must not drop the index. */
    @Test
    void theUsersCollectionKeepsItsCoreFieldsAndUniqueUsernameIndex() {
        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        CollectionDefinition before = collections.findDefinition(ctx, "users");

        try {
            String schema = """
                    {"version":"1","collections":[
                      {"name":"users","fields":[],"indexes":[],"rules":{"ownerField":"owner"}}
                    ],"hooks":[]}
                    """;

            TestResponse response = AdminTestUtils.postWithAdminCookies(
                    IMPORT_URI, cookies, schema, "application/json");
            assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.OK));

            CollectionDefinition users = collections.findDefinition(ctx, "users");
            assertThat(users.fields().stream().map(field -> field.name()).toList(), hasItem("username"));
            assertThat(users.fields().stream().map(field -> field.name()).toList(), hasItem("password"));

            IndexDefinition username = users.indexes().stream()
                    .filter(index -> index.fields().stream().anyMatch(f -> "username".equals(f.field())))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the unique username index is gone"));
            assertThat("the username index must stay unique", username.unique(), is(true));
        } finally {
            collections.replaceDefinition(ctx, before);
            collections.syncIndexes(ctx, "users", before.indexes());
        }
    }

    /** Validating membership rules against the database alone would refuse every fresh group-setup import. */
    @Test
    void aMembershipRuleMayPointAtACollectionFromTheSameFile() {
        String memberships = "schemaval_members_" + DbUtils.id();
        String posts = "schemaval_posts_" + DbUtils.id();

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        // The collection carrying the rule comes first, so file order cannot be what makes this work.
        String schema = """
                {"version":"1","collections":[
                  {"name":"%s","fields":[
                     {"name":"title","type":"STRING","required":true},
                     {"name":"crew","type":"STRING","required":true}],
                   "indexes":[],
                   "rules":{"listRule":"group","viewRule":"group","createRule":"group",
                            "updateRule":"group","deleteRule":"group","ownerField":"owner",
                            "groupCollection":"%s","groupMemberField":"user",
                            "groupField":"crew","groupRecordField":"crew"}},
                  {"name":"%s","fields":[
                     {"name":"user","type":"STRING","required":true},
                     {"name":"crew","type":"STRING","required":true}],
                   "indexes":[],"rules":{"ownerField":"owner"}}
                ],"hooks":[]}
                """.formatted(posts, memberships, memberships);

        TestResponse response = AdminTestUtils.postWithAdminCookies(
                IMPORT_URI, cookies, schema, "application/json");

        assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(definition(posts).rules().groupCollection(), equalTo(memberships));
        assertThat(definition(memberships), org.hamcrest.Matchers.notNullValue());
    }

    @Test
    void aMembershipRulePointingAtAnUnknownCollectionIsRejected() {
        String posts = "schemaval_orphan_" + DbUtils.id();

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        String schema = """
                {"version":"1","collections":[
                  {"name":"%s","fields":[
                     {"name":"title","type":"STRING","required":true},
                     {"name":"crew","type":"STRING","required":true}],
                   "indexes":[],
                   "rules":{"listRule":"group","ownerField":"owner",
                            "groupCollection":"schemaval_nowhere","groupMemberField":"user",
                            "groupField":"crew","groupRecordField":"crew"}}
                ],"hooks":[]}
                """.formatted(posts);

        TestResponse response = AdminTestUtils.postWithAdminCookies(
                IMPORT_URI, cookies, schema, "application/json");

        assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("membership collection"));
        assertThat(definition(posts), nullValue());
    }

    /** The second insert would collide with the unique name index after the first was already stored. */
    @Test
    void aDuplicateCollectionEntryIsRejected() {
        String collection = "schemaval_dupe_" + DbUtils.id();

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        String schema = """
                {"version":"1","collections":[
                  {"name":"%s","fields":[{"name":"title","type":"STRING","required":true}],"indexes":[],
                   "rules":{"listRule":"*","ownerField":"owner"}},
                  {"name":"%s","fields":[{"name":"other","type":"STRING","required":true}],"indexes":[],
                   "rules":{"listRule":"*","ownerField":"owner"}}
                ],"hooks":[]}
                """.formatted(collection, collection);

        TestResponse response = AdminTestUtils.postWithAdminCookies(
                IMPORT_URI, cookies, schema, "application/json");

        assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("more than one entry"));
        assertThat(definition(collection), nullValue());
    }

    /** In a {@code $set} a dot addresses a nested path and a dollar sign starts an operator. */
    @Test
    void aFieldNameWithMongoSyntaxIsRejectedAndNothingIsWritten() {
        String dotted = "schemaval_dotted_" + DbUtils.id();
        String dollar = "schemaval_dollar_" + DbUtils.id();

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        TestResponse withDot = AdminTestUtils.postWithAdminCookies(IMPORT_URI, cookies, """
                {"version":"1","collections":[
                  {"name":"%s","fields":[{"name":"author.name","type":"STRING","required":true}],"indexes":[],
                   "rules":{"listRule":"*","ownerField":"owner"}}
                ],"hooks":[]}
                """.formatted(dotted), "application/json");

        assertThat(withDot.getContent(), withDot.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(withDot.getContent(), containsString("author.name"));
        assertThat(definition(dotted), nullValue());

        TestResponse withDollar = AdminTestUtils.postWithAdminCookies(IMPORT_URI, cookies, """
                {"version":"1","collections":[
                  {"name":"%s","fields":[{"name":"$set","type":"STRING","required":true}],"indexes":[],
                   "rules":{"listRule":"*","ownerField":"owner"}}
                ],"hooks":[]}
                """.formatted(dollar), "application/json");

        assertThat(withDollar.getContent(), withDollar.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(definition(dollar), nullValue());
    }

    @Test
    void aCollectionNameWithMongoSyntaxIsRejected() {
        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        String name = "schemaval.dotted." + DbUtils.id();
        TestResponse response = AdminTestUtils.postWithAdminCookies(IMPORT_URI, cookies, """
                {"version":"1","collections":[
                  {"name":"%s","fields":[{"name":"title","type":"STRING","required":true}],"indexes":[],
                   "rules":{"listRule":"*","ownerField":"owner"}}
                ],"hooks":[]}
                """.formatted(name), "application/json");

        assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("Collection name"));
        assertThat(definition(name), nullValue());
    }

    private static void createHook(AdminTestUtils.AdminCookies cookies, String collection, String name) {
        TestResponse created = AdminTestUtils.postWithAdminCookies(
                "/api/meta/collections/" + collection + "/hooks",
                cookies,
                """
                {"name":"%s","event":"afterCreate","url":"https://example.com/hook",
                 "secret":"a-signing-secret","enabled":true,"priority":50}
                """.formatted(name),
                "application/json");
        assertThat(created.getContent(), created.getStatusCode(), equalTo(StatusCodes.CREATED));
    }

    private static CollectionDefinition definition(String name) {
        return Application.getInstance(TenantCollectionService.class)
                .findDefinition(TenantTestUtils.defaultTenantContext(), name);
    }
}
