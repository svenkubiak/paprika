package controllers;

import auth.TenantContext;
import dtos.SchemaExportDto;
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * Rules ride along in a schema export only because the whole CollectionDefinition document is
 * serialized - nothing states that intent. A projection on the meta query or a reshuffle of the
 * DTO would drop them without any other test noticing, which is what these cover.
 */
@ExtendWith({TestRunner.class})
class SchemaExportImportIntegrationTest {
    private static final String EXPORT_URI = "/api/meta/schema/export";
    private static final String IMPORT_URI = "/api/meta/schema/import";

    @Test
    void rulesSurviveAnExportImportRoundTrip() throws Exception {
        String collection = "schema_rt_" + DbUtils.id();
        // Every level gets a different value, so swapped fields fail here too, and the owner field
        // is not the default - a hardcoded fallback would pass an all-defaults fixture.
        CollectionRules rules = new CollectionRules("*", "auth", "owner", "owner", "", "author");
        TenantTestUtils.seedCollection(collection, rules);

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        TestResponse export = AdminTestUtils.getWithAdminCookies(EXPORT_URI, cookies);
        assertThat(export.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(exportedRules(export, collection), equalTo(rules));

        // Wipe the rules in the database so the import has something to actually restore. Without
        // this the assertion below would pass on an import that silently does nothing.
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        replaceRules(collections, ctx, collection, CollectionRules.locked());
        assertThat(collections.findDefinition(ctx, collection).rules().viewRule(), nullValue());

        TestResponse importResponse = AdminTestUtils.postWithAdminCookies(
                IMPORT_URI, cookies, export.getContent(), "application/json");
        assertThat(importResponse.getStatusCode(), equalTo(StatusCodes.OK));

        assertThat(collections.findDefinition(ctx, collection).rules(), equalTo(rules));
    }

    /**
     * The export is the only producer of these files, but not their only source: they get
     * hand-edited and generated. An entry without a rules object used to be applied as a null,
     * which falls through rulesOrDefault() to locked() and cuts off API access to a collection
     * that was working a moment ago.
     */
    @Test
    void anImportWithoutRulesKeepsTheExistingOnesAndSaysSo() {
        String collection = "schema_norules_" + DbUtils.id();
        CollectionRules rules = new CollectionRules("*", "*", "auth", "auth", "auth", "owner");
        TenantTestUtils.seedCollection(collection, rules);

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        TestResponse response = AdminTestUtils.postWithAdminCookies(
                IMPORT_URI, cookies, schemaWithoutRules(collection), "application/json");
        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat("an import that leaves rules alone must not do so silently",
                response.getContent(), containsString("\"rulesPreserved\":1"));

        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        assertThat(collections.findDefinition(ctx, collection).rules(), equalTo(rules));
    }

    /** A rules object that is present is applied as it is, including a deliberate full lock. */
    @Test
    void anImportWithRulesStillOverwritesThem() {
        String collection = "schema_lock_" + DbUtils.id();
        TenantTestUtils.seedCollection(collection, new CollectionRules("*", "*", "*", "*", "*", "owner"));

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        TestResponse response = AdminTestUtils.postWithAdminCookies(
                IMPORT_URI, cookies, schemaWithLockedRules(collection), "application/json");
        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), not(containsString("\"rulesPreserved\":1")));

        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        CollectionRules applied = collections.findDefinition(ctx, collection).rules();
        assertThat("an explicit lock has to win over the open rules that were there",
                applied.listRule(), nullValue());
        assertThat(applied.ownerField(), equalTo("owner"));
    }

    /**
     * Fields and indexes are what an import is for, so a missing one is an incomplete file rather
     * than "leave it alone". Applying it would wipe the schema of a live collection.
     */
    @Test
    void anImportWithoutFieldsOrIndexesIsRejectedWholesale() {
        String intact = "schema_intact_" + DbUtils.id();
        String broken = "schema_broken_" + DbUtils.id();
        TenantTestUtils.seedCollection(intact, new CollectionRules("*", "*", "*", "*", "*", "owner"));
        TenantTestUtils.seedCollection(broken, new CollectionRules("*", "*", "*", "*", "*", "owner"));

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        // The intact entry comes first on purpose: it would already be written by the time the
        // broken one is reached, which is what validating up front prevents.
        String schema = """
                {"version":"1","collections":[
                  {"name":"%s","fields":[{"name":"title","type":"STRING","required":true}],"indexes":[]},
                  {"name":"%s","indexes":[]}
                ],"hooks":[]}
                """.formatted(intact, broken);

        TestResponse response = AdminTestUtils.postWithAdminCookies(
                IMPORT_URI, cookies, schema, "application/json");

        assertThat("an incomplete file is the caller's mistake, not a server fault",
                response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString(broken + " is missing \\\"fields\\\""));

        // Nothing at all may have been applied, including the entry that was fine.
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        assertThat("a rejected import must not have written the entries before the broken one",
                collections.findDefinition(ctx, intact).fields().size(), equalTo(1));
        assertThat(collections.findDefinition(ctx, intact).fields().getFirst().name(), equalTo("title"));
    }

    @Test
    void aMissingIndexesArrayIsRejectedToo() {
        String collection = "schema_noidx_" + DbUtils.id();
        TenantTestUtils.seedCollection(collection, new CollectionRules("*", "*", "*", "*", "*", "owner"));

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        String schema = """
                {"version":"1","collections":[
                  {"name":"%s","fields":[{"name":"title","type":"STRING","required":true}]}
                ],"hooks":[]}
                """.formatted(collection);

        TestResponse response = AdminTestUtils.postWithAdminCookies(
                IMPORT_URI, cookies, schema, "application/json");

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString(collection + " is missing \\\"indexes\\\""));
    }

    /**
     * The membership configuration of the group/peers presets is as much part of a rule as the
     * rule value itself - a round trip that loses it would restore a collection whose rules deny
     * everything, or worse, whose meaning quietly changed.
     */
    @Test
    void theMembershipConfigurationSurvivesARoundTrip() throws Exception {
        String memberships = "schema_memberships_" + DbUtils.id();
        String collection = "schema_group_" + DbUtils.id();
        CollectionRules rules = new CollectionRules(
                "group", "group", "group", "group", "group",
                "owner", memberships, "user", "crew", "crew");

        TenantTestUtils.seedCollection(memberships, CollectionRules.locked());
        TenantTestUtils.seedCollection(collection, rules);

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        TestResponse export = AdminTestUtils.getWithAdminCookies(EXPORT_URI, cookies);
        assertThat(export.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(exportedRules(export, collection), equalTo(rules));

        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        replaceRules(collections, ctx, collection, CollectionRules.locked());

        TestResponse importResponse = AdminTestUtils.postWithAdminCookies(
                IMPORT_URI, cookies, export.getContent(), "application/json");
        assertThat(importResponse.getStatusCode(), equalTo(StatusCodes.OK));

        CollectionRules restored = collections.findDefinition(ctx, collection).rules();
        assertThat(restored, equalTo(rules));
        assertThat(restored.groupCollection(), equalTo(memberships));
        assertThat(restored.groupMemberField(), equalTo("user"));
        assertThat(restored.groupField(), equalTo("crew"));
        assertThat(restored.groupRecordField(), equalTo("crew"));
    }

    /** The group collection itself: groupRecordField "id" has to come back as "id", not as null. */
    @Test
    void theGroupCollectionConfigurationSurvivesARoundTrip() throws Exception {
        String memberships = "schema_selfmemberships_" + DbUtils.id();
        String collection = "schema_selfgroup_" + DbUtils.id();
        CollectionRules rules = new CollectionRules(
                "group", "group", "auth", "group", "group",
                "owner", memberships, "user", "team", "id");

        TenantTestUtils.seedCollection(memberships, CollectionRules.locked());
        TenantTestUtils.seedCollection(collection, rules);

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        TestResponse export = AdminTestUtils.getWithAdminCookies(EXPORT_URI, cookies);
        assertThat(export.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(exportedRules(export, collection), equalTo(rules));

        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        replaceRules(collections, ctx, collection, CollectionRules.locked());

        TestResponse importResponse = AdminTestUtils.postWithAdminCookies(
                IMPORT_URI, cookies, export.getContent(), "application/json");
        assertThat(importResponse.getContent(), importResponse.getStatusCode(), equalTo(StatusCodes.OK));

        CollectionRules restored = collections.findDefinition(ctx, collection).rules();
        assertThat(restored, equalTo(rules));
        assertThat(restored.groupRecordField(), equalTo("id"));
    }

    /**
     * The export is the one producer of these files, so whatever it writes has to pass the import's
     * own validation - otherwise a tenant could end up with a backup the instance refuses to read.
     */
    @Test
    void anExportIsAlwaysAcceptedByTheImport() throws Exception {
        String collection = "schema_selfimport_" + DbUtils.id();
        TenantTestUtils.seedCollection(collection, new CollectionRules("*", "auth", "auth", "auth", "", "owner"));

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        TestResponse export = AdminTestUtils.getWithAdminCookies(EXPORT_URI, cookies);
        assertThat(export.getStatusCode(), equalTo(StatusCodes.OK));

        SchemaExportDto exported = JsonUtils.getMapper().readValue(export.getContent(), SchemaExportDto.class);
        for (CollectionDefinition definition : exported.collections()) {
            assertThat(definition.name() + " was exported without fields",
                    definition.fields(), not(nullValue()));
            assertThat(definition.name() + " was exported without indexes",
                    definition.indexes(), not(nullValue()));
        }

        TestResponse reimport = AdminTestUtils.postWithAdminCookies(
                IMPORT_URI, cookies, export.getContent(), "application/json");
        assertThat(reimport.getStatusCode(), equalTo(StatusCodes.OK));
    }

    /**
     * Field options ride along for the same reason the rules do - nothing states that intent - so
     * the newest one gets its own round trip. Losing it would silently disable image variants for
     * every restored tenant.
     */
    @Test
    void imageWidthsSurviveAnExportImportRoundTrip() throws Exception {
        String collection = "schema_widths_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                CollectionRules.locked(),
                java.util.List.of(
                        new models.FieldDefinition("title", enums.FieldType.STRING, true, false, null),
                        new models.FieldDefinition("picture", enums.FieldType.FILE, false, true,
                                models.FieldOptions.forFile(1024, java.util.List.of(), 1,
                                        java.util.List.of(320, 800)))));

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
        TestResponse export = AdminTestUtils.getWithAdminCookies(EXPORT_URI, cookies);
        assertThat(export.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(exportedImageWidths(export, collection), equalTo(java.util.List.of(320, 800)));

        // Wipe the option in the database, so an import that does nothing cannot pass this.
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        CollectionDefinition current = collections.findDefinition(ctx, collection);
        collections.replaceDefinition(ctx, new CollectionDefinition(
                current.id(),
                current.name(),
                java.util.List.of(
                        new models.FieldDefinition("title", enums.FieldType.STRING, true, false, null),
                        new models.FieldDefinition("picture", enums.FieldType.FILE, false, true,
                                models.FieldOptions.forFile(1024, java.util.List.of(), 1))),
                current.indexes(),
                current.rules(),
                current.system()));
        assertThat(storedImageWidths(collections, ctx, collection), equalTo(java.util.List.of()));

        TestResponse importResponse = AdminTestUtils.postWithAdminCookies(
                IMPORT_URI, cookies, export.getContent(), "application/json");
        assertThat(importResponse.getStatusCode(), equalTo(StatusCodes.OK));

        assertThat(storedImageWidths(collections, ctx, collection), equalTo(java.util.List.of(320, 800)));
    }

    private static java.util.List<Integer> exportedImageWidths(TestResponse export, String collection)
            throws Exception {
        SchemaExportDto exported = JsonUtils.getMapper().readValue(export.getContent(), SchemaExportDto.class);
        return exported.collections().stream()
                .filter(definition -> collection.equals(definition.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the export is missing " + collection))
                .fields().stream()
                .filter(field -> "picture".equals(field.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the export is missing the picture field"))
                .optionsOrDefault()
                .imageWidthsOrEmpty();
    }

    private static java.util.List<Integer> storedImageWidths(
            TenantCollectionService collections, TenantContext ctx, String collection) {
        return collections.findDefinition(ctx, collection).fields().stream()
                .filter(field -> "picture".equals(field.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the definition is missing the picture field"))
                .optionsOrDefault()
                .imageWidthsOrEmpty();
    }

    private static CollectionRules exportedRules(TestResponse export, String collection) throws Exception {
        SchemaExportDto exported = JsonUtils.getMapper().readValue(export.getContent(), SchemaExportDto.class);

        return exported.collections().stream()
                .filter(definition -> collection.equals(definition.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the export is missing " + collection))
                .rules();
    }

    private static String schemaWithoutRules(String collection) {
        return """
                {"version":"1","collections":[
                  {"name":"%s","fields":[{"name":"title","type":"STRING","required":true}],"indexes":[]}
                ],"hooks":[]}
                """.formatted(collection);
    }

    private static String schemaWithLockedRules(String collection) {
        return """
                {"version":"1","collections":[
                  {"name":"%s","fields":[{"name":"title","type":"STRING","required":true}],"indexes":[],
                   "rules":{"ownerField":"owner"}}
                ],"hooks":[]}
                """.formatted(collection);
    }

    private static void replaceRules(
            TenantCollectionService collections,
            TenantContext ctx,
            String name,
            CollectionRules rules) {
        CollectionDefinition current = collections.findDefinition(ctx, name);
        collections.replaceDefinition(ctx, new CollectionDefinition(
                current.id(),
                current.name(),
                current.fields(),
                current.indexes(),
                rules,
                current.system()
        ));
    }
}
