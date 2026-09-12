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

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

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
                .list(ctx, collection, new Request(), 0, 25);

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
                .list(ctx, collection, request, 0, 25);

        assertThat(result.status(), is(CollectionRecordService.RecordResult.Status.FORBIDDEN));
    }
}
