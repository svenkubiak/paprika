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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;

/**
 * The sort parameter has to arrive at the service through the same name-based query binding the
 * filter uses; a unit test on the service alone would not notice a binding that never fires.
 */
@ExtendWith({TestRunner.class})
class CollectionSortIntegrationTest {

    @Test
    void sortParameterIsBoundAndApplied() {
        String collection = seed();
        insert(collection, "banana");
        insert(collection, "apple");
        insert(collection, "cherry");

        String ascending = get(collection, "?offset=0&limit=25&sort=title:asc").getContent();
        assertThat(ascending.indexOf("apple"), lessThan(ascending.indexOf("banana")));
        assertThat(ascending.indexOf("banana"), lessThan(ascending.indexOf("cherry")));

        String descending = get(collection, "?offset=0&limit=25&sort=title:desc").getContent();
        assertThat(descending.indexOf("cherry"), lessThan(descending.indexOf("banana")));
        assertThat(descending.indexOf("banana"), lessThan(descending.indexOf("apple")));
    }

    @Test
    void invalidSortIsAFourHundred() {
        String collection = seed();
        insert(collection, "only");

        assertThat(get(collection, "?sort=nope:asc").getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(get(collection, "?sort=title:sideways").getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(get(collection, "?sort=title").getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
    }

    @Test
    void absentSortStillAnswers() {
        String collection = seed();
        insert(collection, "only");

        assertThat(get(collection, "?offset=0&limit=25").getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(get(collection, "?offset=0&limit=25&sort=").getStatusCode(), equalTo(StatusCodes.OK));
    }

    private static String seed() {
        String collection = "notes_sort_http_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "*", "*", "*", null),
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null)));
        return collection;
    }

    private static void insert(String collection, String title) {
        Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), collection)
                .insertOne(new Document().append("id", DbUtils.id()).append("title", title));
    }

    private static TestResponse get(String collection, String query) {
        return TestRequest.get("/api/collections/" + collection + query).execute();
    }
}
