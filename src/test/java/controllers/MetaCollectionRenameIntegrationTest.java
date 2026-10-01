package controllers;

import auth.TenantContext;
import constants.CollectionName;
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
import services.TenantDatabaseResolver;
import utils.AdminTestUtils;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * A rename moves the definition and the MongoDB collection, which must stay in step: a refused
 * rename leaves both as they were, an accepted one moves both.
 */
@ExtendWith({TestRunner.class})
class MetaCollectionRenameIntegrationTest {

    private static final CollectionRules OPEN = new CollectionRules("*", "*", "*", "*", "*", "owner");

    @Test
    void renamingACollectionMovesItsRecordsAndItsDefinitionTogether() {
        String before = "rename_ok_" + DbUtils.id();
        String after = "renamed_ok_" + DbUtils.id();
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);

        TenantTestUtils.seedCollection(before, OPEN);
        String id = collections.findDefinition(ctx, before).id();
        String recordId = TenantTestUtils.seedRecord(before, "first");
        TenantTestUtils.seedRecord(before, "second");

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
        TestResponse renamed = patchDefinition(before, id, after, cookies, uniqueTitleIndex(false));

        assertThat(renamed.getContent(), renamed.getStatusCode(), equalTo(StatusCodes.OK));

        assertThat(collections.findDefinition(ctx, before), nullValue());
        CollectionDefinition definition = collections.findDefinition(ctx, after);
        assertThat(definition, notNullValue());
        assertThat(definition.id(), equalTo(id));

        assertThat(collections.dataCollection(ctx, after).countDocuments(), is(2L));
        assertThat(physicalCollections(ctx), hasItem(CollectionName.physicalTenantData(after)));
        assertThat(physicalCollections(ctx), not(hasItem(CollectionName.physicalTenantData(before))));
        assertThat(hasIndex(collections, ctx, after), is(true));

        assertThat(read(after, recordId).getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(read(before, recordId).getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
    }

    /** A 400 for an unbuildable index must mean nothing moved, including the rename. */
    @Test
    void aRefusedIndexChangeInTheSameRequestLeavesTheCollectionUnrenamed() {
        String before = "rename_reject_" + DbUtils.id();
        String after = "renamed_reject_" + DbUtils.id();
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);

        TenantTestUtils.seedCollection(before, OPEN);
        String id = collections.findDefinition(ctx, before).id();
        String recordId = TenantTestUtils.seedRecord(before, "same");
        TenantTestUtils.seedRecord(before, "same");

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
        TestResponse rejected = patchDefinition(before, id, after, cookies, uniqueTitleIndex(true));

        assertThat(rejected.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(rejected.getContent(), containsString("title_idx"));

        assertThat(collections.findDefinition(ctx, after), nullValue());
        CollectionDefinition definition = collections.findDefinition(ctx, before);
        assertThat(definition, notNullValue());
        assertThat(definition.name(), equalTo(before));

        // The data must not have moved to the new physical collection while the definition stayed
        assertThat(physicalCollections(ctx), not(hasItem(CollectionName.physicalTenantData(after))));
        assertThat(collections.dataCollection(ctx, before).countDocuments(), is(2L));
        assertThat(read(before, recordId).getStatusCode(), equalTo(StatusCodes.OK));

        TestResponse list = TestRequest.get("/api/collections/" + before).execute();
        assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(list.getContent(), containsString(recordId));
    }

    @Test
    void renamingOntoAnExistingCollectionIsRejectedWithoutMovingAnything() {
        String before = "rename_clash_" + DbUtils.id();
        String taken = "rename_taken_" + DbUtils.id();
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);

        TenantTestUtils.seedCollection(before, OPEN);
        TenantTestUtils.seedCollection(taken, OPEN);
        String id = collections.findDefinition(ctx, before).id();
        String recordId = TenantTestUtils.seedRecord(before, "first");
        TenantTestUtils.seedRecord(taken, "other");

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
        TestResponse rejected = patchDefinition(before, id, taken, cookies, uniqueTitleIndex(false));

        assertThat(rejected.getStatusCode(), equalTo(StatusCodes.CONFLICT));

        assertThat(collections.findDefinition(ctx, before), notNullValue());
        assertThat(collections.findDefinition(ctx, taken).id(), not(equalTo(id)));
        assertThat(collections.dataCollection(ctx, before).countDocuments(), is(1L));
        assertThat(collections.dataCollection(ctx, taken).countDocuments(), is(1L));
        assertThat(read(before, recordId).getStatusCode(), equalTo(StatusCodes.OK));
    }

    /** Guards the common path against regressions from running the index sync before the rename. */
    @Test
    void aRefusedIndexChangeWithoutARenameStillStoresNothing() {
        String collection = "rename_none_" + DbUtils.id();
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);

        TenantTestUtils.seedCollection(collection, OPEN);
        String id = collections.findDefinition(ctx, collection).id();
        String recordId = TenantTestUtils.seedRecord(collection, "same");
        TenantTestUtils.seedRecord(collection, "same");

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
        TestResponse rejected = patchDefinition(collection, id, collection, cookies, uniqueTitleIndex(true));

        assertThat(rejected.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(collections.findDefinition(ctx, collection).indexes(), is(org.hamcrest.Matchers.empty()));
        assertThat(collections.dataCollection(ctx, collection).countDocuments(), is(2L));
        assertThat(read(collection, recordId).getStatusCode(), equalTo(StatusCodes.OK));
    }

    private static String uniqueTitleIndex(boolean unique) {
        return """
                {"name": "title_idx", "fields": [{"field": "title", "direction": "ASC"}], "unique": %s}
                """.formatted(unique);
    }

    private static TestResponse patchDefinition(
            String collection,
            String id,
            String newName,
            AdminTestUtils.AdminCookies cookies,
            String indexJson) {

        return AdminTestUtils.patchWithAdminCookies(
                "/api/meta/collections/" + collection + "/" + id,
                cookies,
                """
                {
                  "name": "%s",
                  "fields": [{"name": "title", "type": "STRING", "required": true, "nullable": false}],
                  "indexes": [%s]
                }
                """.formatted(newName, indexJson),
                "application/json");
    }

    private static TestResponse read(String collection, String recordId) {
        return TestRequest.get("/api/collections/" + collection + "/" + recordId).execute();
    }

    private static Set<String> physicalCollections(TenantContext ctx) {
        return Application.getInstance(TenantDatabaseResolver.class)
                .tenant(ctx)
                .listCollectionNames()
                .into(new HashSet<>());
    }

    private static boolean hasIndex(TenantCollectionService collections, TenantContext ctx, String collection) {
        for (var index : collections.dataCollection(ctx, collection).listIndexes()) {
            if ("title_idx".equals(index.getString("name"))) {
                return true;
            }
        }
        return false;
    }
}
