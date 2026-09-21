package services;

import auth.AuthorizationDecision;
import auth.TenantContext;
import enums.FieldType;
import rules.RuleOperation;
import io.mangoo.core.Application;
import io.mangoo.routing.bindings.Request;
import io.mangoo.test.TestRunner;
import models.CollectionRules;
import models.FieldDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.DbUtils;
import utils.TenantTestUtils;

import com.mongodb.client.model.Filters;
import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@ExtendWith({TestRunner.class})
class CollectionRecordServiceListTest {

    @Test
    void listRefusesWhenAuthFilterSuppliedNoFilter() {
        String collection = "notes_failclosed_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("owner", "owner", "auth", "owner", "owner", "owner"),
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null)));

        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        Application.getInstance(TenantCollectionService.class)
                .dataCollection(ctx, collection)
                .insertOne(new org.bson.Document().append("id", DbUtils.id()).append("title", "SECRET"));

        // A request that never passed through ApiAuthFilter carries no authorization decision.
        // Listing unfiltered here would return every record, so the service has to refuse instead.
        CollectionRecordService.RecordResult result = Application.getInstance(CollectionRecordService.class)
                .list(ctx, collection, new Request(), 0, 25, null, null);

        assertThat(result.status(), is(CollectionRecordService.RecordResult.Status.FORBIDDEN));
        assertThat(result.body(), is((Object) null));
    }

    /**
     * A decision for a single record operation carries no scoping query, as there is nothing to
     * scope. Reusing it for a LIST would list the whole collection, so it must be refused as well:
     * the presence of a decision alone is not permission to list.
     */
    @Test
    void listRefusesDecisionWithoutScopingQuery() {
        String collection = "notes_noscope_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("owner", "owner", "auth", "owner", "owner", "owner"),
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null)));

        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        Application.getInstance(TenantCollectionService.class)
                .dataCollection(ctx, collection)
                .insertOne(new org.bson.Document().append("id", DbUtils.id()).append("title", "SECRET"));

        Request request = new Request();
        AuthorizationDecision.granted(RuleOperation.VIEW).storeIn(request);

        CollectionRecordService.RecordResult result = Application.getInstance(CollectionRecordService.class)
                .list(ctx, collection, request, 0, 25, null, null);

        assertThat(result.status(), is(CollectionRecordService.RecordResult.Status.FORBIDDEN));
    }

    /**
     * Without a sort MongoDB may return the same record on two pages, or none at all, once a write
     * lands between the two requests. Paginating across a concurrent insert and delete is the only
     * assertion that catches that - "the page is sorted" would pass on an unordered cursor too.
     */
    @Test
    void paginationStaysStableAcrossWrites() {
        String collection = seededCollection("notes_pagination_");
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        List<String> seeded = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            seeded.add(insert(ctx, collection, "title-" + i));
        }

        Set<String> seen = new LinkedHashSet<>();
        List<String> firstPage = idsOf(list(ctx, collection, 0, 10, null));
        seen.addAll(firstPage);

        // A write between two page requests is exactly the situation an unordered cursor cannot
        // survive: the new record sorts after everything already seen, the deleted one is gone.
        insert(ctx, collection, "title-inserted");
        String deleted = seeded.getLast();
        Application.getInstance(TenantCollectionService.class)
                .dataCollection(ctx, collection)
                .deleteOne(Filters.eq("id", deleted));

        List<String> secondPage = idsOf(list(ctx, collection, 10, 10, null));
        List<String> thirdPage = idsOf(list(ctx, collection, 20, 10, null));

        assertThat("pages must not repeat a record", secondPage, everyItem(not(in(seen))));
        seen.addAll(secondPage);
        assertThat("pages must not repeat a record", thirdPage, everyItem(not(in(seen))));
        seen.addAll(thirdPage);

        // Every record that existed before and after the writes has to show up exactly once.
        for (String id : seeded) {
            if (!id.equals(deleted)) {
                assertThat("record " + id + " fell out of the pagination", seen.contains(id), is(true));
            }
        }
    }

    /**
     * A limit above the maximum used to fall back to the default of 25, which hands the client a
     * plausible looking but far too short page. It is clamped to the maximum instead.
     */
    @Test
    void limitIsClampedToTheMaximumInsteadOfFallingBackToTheDefault() {
        String collection = seededCollection("notes_limit_");
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        for (int i = 0; i < 110; i++) {
            insert(ctx, collection, "title-" + i);
        }

        assertThat(idsOf(list(ctx, collection, 0, 0, null)), hasSize(25));
        assertThat(idsOf(list(ctx, collection, 0, -1, null)), hasSize(25));
        assertThat(idsOf(list(ctx, collection, 0, 100, null)), hasSize(100));
        assertThat(idsOf(list(ctx, collection, 0, 101, null)), hasSize(100));
        assertThat(idsOf(list(ctx, collection, 0, 10_000, null)), hasSize(100));
    }

    @Test
    void sortsAscendingAndDescendingOnASchemaField() {
        String collection = seededCollection("notes_sort_");
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        insert(ctx, collection, "banana");
        insert(ctx, collection, "apple");
        insert(ctx, collection, "cherry");

        assertThat(titlesOf(list(ctx, collection, 0, 25, null, "title:asc")),
                is(List.of("apple", "banana", "cherry")));
        assertThat(titlesOf(list(ctx, collection, 0, 25, null, "title:desc")),
                is(List.of("cherry", "banana", "apple")));
    }

    @Test
    void sortsOnASystemTimestamp() {
        String collection = seededCollection("notes_sortsystem_");
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        insertWithCreatedAt(ctx, collection, "second", "2024-01-02T00:00:00Z");
        insertWithCreatedAt(ctx, collection, "first", "2024-01-01T00:00:00Z");
        insertWithCreatedAt(ctx, collection, "third", "2024-01-03T00:00:00Z");

        assertThat(titlesOf(list(ctx, collection, 0, 25, null, "createdAt:asc")),
                is(List.of("first", "second", "third")));
        assertThat(titlesOf(list(ctx, collection, 0, 25, null, "createdAt:desc")),
                is(List.of("third", "second", "first")));
    }

    @Test
    void combinesSortWithFilter() {
        String collection = seededCollection("notes_sortfilter_");
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        insert(ctx, collection, "keep");
        insert(ctx, collection, "drop");
        insert(ctx, collection, "keep");

        assertThat(titlesOf(list(ctx, collection, 0, 25, "title:eq:keep", "title:desc")),
                is(List.of("keep", "keep")));
    }

    @Test
    void emptySortBehavesLikeNoSort() {
        String collection = seededCollection("notes_sortempty_");
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        insert(ctx, collection, "b");
        insert(ctx, collection, "a");

        assertThat(titlesOf(list(ctx, collection, 0, 25, null, "")),
                is(titlesOf(list(ctx, collection, 0, 25, null))));
    }

    /**
     * An invalid sort must never be answered with an arbitrarily ordered page: the client asked for
     * an order and would have no way of telling it did not get one.
     */
    @Test
    void invalidSortIsRejectedInsteadOfSilentlyIgnored() {
        String collection = "notes_sortinvalid_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "*", "*", "*", null),
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("payload", FieldType.JSON, false, true, null),
                        new FieldDefinition("attachment", FieldType.FILE, false, true, null)));
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        insert(ctx, collection, "only");

        for (String sort : List.of("nope:asc", "title:sideways", "payload:asc", "attachment:desc", "title", ":asc")) {
            assertThat("sort '" + sort + "' must be rejected",
                    list(ctx, collection, 0, 25, null, sort).status(),
                    is(CollectionRecordService.RecordResult.Status.BAD_REQUEST));
        }
    }

    static String seededCollection(String prefix) {
        String collection = prefix + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "*", "*", "*", null),
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null)));
        return collection;
    }

    static String insert(TenantContext ctx, String collection, String title) {
        String id = DbUtils.id();
        Application.getInstance(TenantCollectionService.class)
                .dataCollection(ctx, collection)
                .insertOne(new Document()
                        .append("id", id)
                        .append("title", title)
                        .append(constants.SystemFields.CREATED_AT, constants.SystemFields.timestamp()));
        return id;
    }

    static void insertWithCreatedAt(TenantContext ctx, String collection, String title, String createdAt) {
        Application.getInstance(TenantCollectionService.class)
                .dataCollection(ctx, collection)
                .insertOne(new Document()
                        .append("id", DbUtils.id())
                        .append("title", title)
                        .append(constants.SystemFields.CREATED_AT, createdAt));
    }

    static CollectionRecordService.RecordResult list(
            TenantContext ctx, String collection, int offset, int limit, String filter) {
        return list(ctx, collection, offset, limit, filter, null);
    }

    static CollectionRecordService.RecordResult list(
            TenantContext ctx, String collection, int offset, int limit, String filter, String sort) {
        Request request = new Request();
        AuthorizationDecision.listGranted(new Document()).storeIn(request);
        return Application.getInstance(CollectionRecordService.class)
                .list(ctx, collection, request, offset, limit, filter, sort);
    }

    @SuppressWarnings("unchecked")
    static List<String> idsOf(CollectionRecordService.RecordResult result) {
        Map<String, Object> body = (Map<String, Object>) result.body();
        List<Document> items = (List<Document>) body.get("items");
        return items.stream().map(item -> item.getString("id")).toList();
    }

    @SuppressWarnings("unchecked")
    static List<String> titlesOf(CollectionRecordService.RecordResult result) {
        Map<String, Object> body = (Map<String, Object>) result.body();
        List<Document> items = (List<Document>) body.get("items");
        return items.stream().map(item -> item.getString("title")).toList();
    }

    private static org.hamcrest.Matcher<String> in(java.util.Collection<String> values) {
        return org.hamcrest.Matchers.in(List.copyOf(values));
    }
}
