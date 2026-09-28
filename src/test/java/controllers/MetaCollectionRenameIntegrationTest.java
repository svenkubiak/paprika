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
 * Renaming a collection moves two things that have to stay in step: the definition, which is what
 * every request resolves a collection through, and the MongoDB collection holding the records.
 * <p>
 * The rename used to happen first and the definition was stored last, with the index sync in
 * between - so an index the request could not build answered 400 <em>after</em> the data had
 * already moved. The definition then still named the old collection, which no longer existed:
 * every record was gone as far as the API was concerned, and the next write created a fresh empty
 * one under the old name. What these tests hold onto is that a refused rename leaves the
 * collection exactly as it was, and an accepted one moves both halves.
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

        // The definition moved, keeping its identity.
        assertThat(collections.findDefinition(ctx, before), nullValue());
        CollectionDefinition definition = collections.findDefinition(ctx, after);
        assertThat(definition, notNullValue());
        assertThat(definition.id(), equalTo(id));

        // ... and so did the records, index included.
        assertThat(collections.dataCollection(ctx, after).countDocuments(), is(2L));
        assertThat(physicalCollections(ctx), hasItem(CollectionName.physicalTenantData(after)));
        assertThat(physicalCollections(ctx), not(hasItem(CollectionName.physicalTenantData(before))));
        assertThat(hasIndex(collections, ctx, after), is(true));

        // The record is readable under the new name and gone under the old one.
        assertThat(read(after, recordId).getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(read(before, recordId).getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
    }

    /**
     * The regression. A unique index over data that is already ambiguous cannot be built, which is
     * a 400 - and a 400 has to mean nothing happened. Renaming in the same request must not turn
     * that refusal into a collection whose records no longer have a definition pointing at them.
     */
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

        // The definition still names the collection it named before.
        assertThat(collections.findDefinition(ctx, after), nullValue());
        CollectionDefinition definition = collections.findDefinition(ctx, before);
        assertThat(definition, notNullValue());
        assertThat(definition.name(), equalTo(before));

        // And both records are still where that definition says they are - this is the assertion
        // the old order of operations failed: the data had moved to the new physical collection
        // while the definition kept pointing at the old one.
        assertThat(physicalCollections(ctx), not(hasItem(CollectionName.physicalTenantData(after))));
        assertThat(collections.dataCollection(ctx, before).countDocuments(), is(2L));
        assertThat(read(before, recordId).getStatusCode(), equalTo(StatusCodes.OK));

        TestResponse list = TestRequest.get("/api/collections/" + before).execute();
        assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(list.getContent(), containsString(recordId));
    }

    /**
     * A rename onto a name that is taken is a conflict, and it is detected before anything moves -
     * two definitions under one name is the state the unique index on {@code name} exists to
     * prevent.
     */
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

    /**
     * Without a rename the index sync behaves as it always did: a refused change reports what is
     * wrong and stores nothing. Kept here so that moving the sync in front of the rename is not
     * paid for with a regression on the far more common path.
     */
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

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

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
