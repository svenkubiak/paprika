package controllers;

import auth.TenantContext;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionDefinition;
import models.CollectionRules;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import utils.AdminTestUtils;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.util.List;
import java.util.stream.StreamSupport;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Changing an index that already exists is not the same as creating one: MongoDB rejects a
 * createIndex that reuses a name with different options (IndexOptionsConflict), so the old index
 * has to be dropped first. Flipping "unique" on an existing index - the one change the admin UI
 * offers most - used to run into exactly that and came back as a 500.
 */
@ExtendWith({TestRunner.class})
class MetaIndexChangeIntegrationTest {

    private static final CollectionRules OPEN = new CollectionRules("*", "*", "*", "*", "*", "owner");

    @Test
    void anExistingIndexCanBeMadeUnique() {
        String collection = "index_unique_" + DbUtils.id();
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        TenantTestUtils.seedCollection(collection, OPEN);
        String id = collections.findDefinition(ctx, collection).id();

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        assertThat(patchIndex(collection, id, cookies, false).getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(isUnique(collections, ctx, collection), is(false));

        TestResponse madeUnique = patchIndex(collection, id, cookies, true);

        assertThat(madeUnique.getContent(), madeUnique.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(isUnique(collections, ctx, collection), is(true));
        assertThat(collections.findDefinition(ctx, collection).indexes().getFirst().unique(), is(true));

        // And back again - dropping the unique constraint is the same operation in reverse.
        assertThat(patchIndex(collection, id, cookies, false).getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(isUnique(collections, ctx, collection), is(false));
    }

    /**
     * Data that is already ambiguous cannot carry a unique index. That is a problem with the
     * request, not with the server, and the answer has to name the index and the reason - plus
     * the collection has to keep the index it had.
     */
    @Test
    void makingAnIndexUniqueOverDuplicateDataIsRejected() {
        String collection = "index_dupes_" + DbUtils.id();
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        TenantTestUtils.seedCollection(collection, OPEN);
        String id = collections.findDefinition(ctx, collection).id();

        TenantTestUtils.seedRecord(collection, "same");
        TenantTestUtils.seedRecord(collection, "same");

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();
        assertThat(patchIndex(collection, id, cookies, false).getStatusCode(), equalTo(StatusCodes.OK));

        TestResponse rejected = patchIndex(collection, id, cookies, true);

        assertThat(rejected.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(rejected.getContent(), containsString("title_idx"));
        assertThat(rejected.getContent(), containsString("title"));

        // Nothing was stored and the non-unique index is still there.
        assertThat(collections.findDefinition(ctx, collection).indexes().getFirst().unique(), is(false));
        assertThat(indexSpec(collections, ctx, collection), is(org.hamcrest.Matchers.notNullValue()));
        assertThat(isUnique(collections, ctx, collection), is(false));
    }

    /**
     * A compound index is ordered and can mix directions - the admin UI builds them, so the meta
     * API has to store exactly what it was given.
     */
    @Test
    void compoundIndexesArePersistedWithOrderAndDirection() {
        String collection = "index_compound_" + DbUtils.id();
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        TenantTestUtils.seedCollection(collection, OPEN);
        String id = collections.findDefinition(ctx, collection).id();

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        TestResponse created = patchDefinition(collection, id, cookies, """
                {"name": "combo", "unique": false, "fields": [
                  {"field": "title", "direction": "ASC"},
                  {"field": "createdAt", "direction": "DESC"}
                ]}
                """);

        assertThat(created.getContent(), created.getStatusCode(), equalTo(StatusCodes.OK));

        Document keys = namedIndex(collections, ctx, collection, "combo").get("key", Document.class);
        assertThat(keys.keySet().stream().toList(), equalTo(List.of("title", "createdAt")));
        assertThat(keys.get("title"), equalTo(1));
        assertThat(keys.get("createdAt"), equalTo(-1));

        // Reordering the fields is a different index - it has to be rebuilt, not left alone.
        TestResponse reordered = patchDefinition(collection, id, cookies, """
                {"name": "combo", "unique": false, "fields": [
                  {"field": "createdAt", "direction": "DESC"},
                  {"field": "title", "direction": "ASC"}
                ]}
                """);

        assertThat(reordered.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(
                namedIndex(collections, ctx, collection, "combo").get("key", Document.class)
                        .keySet().stream().toList(),
                equalTo(List.of("createdAt", "title")));
    }

    /**
     * An index on a field that does not exist describes a broken request, not a broken server.
     * validateDefinition() reports it as an IllegalArgumentException, which used to leave the
     * controller as a 500.
     */
    @Test
    void anIndexOnAnUnknownFieldIsRejectedWithBadRequest() {
        String collection = "index_unknown_field_" + DbUtils.id();
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        TenantTestUtils.seedCollection(collection, OPEN);
        String id = collections.findDefinition(ctx, collection).id();

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        TestResponse response = patchDefinition(collection, id, cookies, """
                {"name": "gone_idx", "unique": false, "fields": [
                  {"field": "does_not_exist", "direction": "ASC"}
                ]}
                """);

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("Unknown index field: does_not_exist"));
    }

    private static TestResponse patchDefinition(
            String collection,
            String id,
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
                """.formatted(collection, indexJson),
                "application/json");
    }

    private static Document namedIndex(
            TenantCollectionService collections,
            TenantContext ctx,
            String collection,
            String name) {
        return StreamSupport
                .stream(collections.dataCollection(ctx, collection).listIndexes().spliterator(), false)
                .filter(index -> name.equals(index.getString("name")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("index " + name + " is missing"));
    }

    private static TestResponse patchIndex(
            String collection,
            String id,
            AdminTestUtils.AdminCookies cookies,
            boolean unique) {
        return AdminTestUtils.patchWithAdminCookies(
                "/api/meta/collections/" + collection + "/" + id,
                cookies,
                """
                {
                  "name": "%s",
                  "fields": [{"name": "title", "type": "STRING", "required": true, "nullable": false}],
                  "indexes": [
                    {"name": "title_idx", "fields": [{"field": "title", "direction": "ASC"}], "unique": %s}
                  ]
                }
                """.formatted(collection, unique),
                "application/json");
    }

    private static Document indexSpec(TenantCollectionService collections, TenantContext ctx, String collection) {
        return StreamSupport
                .stream(collections.dataCollection(ctx, collection).listIndexes().spliterator(), false)
                .filter(index -> "title_idx".equals(index.getString("name")))
                .findFirst()
                .orElse(null);
    }

    private static boolean isUnique(TenantCollectionService collections, TenantContext ctx, String collection) {
        Document spec = indexSpec(collections, ctx, collection);
        if (spec == null) {
            throw new IllegalStateException("index title_idx is missing on " + collection);
        }

        return Boolean.TRUE.equals(spec.getBoolean("unique"));
    }
}
