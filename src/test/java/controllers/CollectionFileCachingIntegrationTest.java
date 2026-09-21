package controllers;

import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import models.CollectionRules;
import models.FieldDefinition;
import models.FieldOptions;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * A download used to carry no cache header at all, so every view transferred the whole file again.
 * The id of a stored file identifies its content - a replacement always gets a new id - so it is a
 * valid strong validator.
 */
@ExtendWith({TestRunner.class})
class CollectionFileCachingIntegrationTest {

    @Test
    void downloadCarriesAStrongEtagAndAPrivateCacheControl() {
        String collection = seed();
        String recordId = upload(collection, "first-content");

        TestResponse download = download(collection, recordId, null);

        assertThat(download.getStatusCode(), equalTo(200));
        assertThat(download.getHeader("ETag"), notNullValue());
        assertThat(download.getHeader("ETag"), containsString("\""));
        // A shared cache must never hand a rule-protected file to a different caller.
        assertThat(download.getHeader("Cache-Control"), containsString("private"));
        assertThat(download.getContent(), containsString("first-content"));
    }

    @Test
    void matchingIfNoneMatchAnswersThreeOhFourWithoutABody() {
        String collection = seed();
        String recordId = upload(collection, "cached-content");

        String etag = download(collection, recordId, null).getHeader("ETag");
        TestResponse revalidated = download(collection, recordId, etag);

        assertThat(revalidated.getStatusCode(), equalTo(304));
        assertThat(revalidated.getContent(), not(containsString("cached-content")));
        assertThat(revalidated.getHeader("ETag"), equalTo(etag));
    }

    @Test
    void aStaleValidatorStillGetsTheFile() {
        String collection = seed();
        String recordId = upload(collection, "fresh-content");

        TestResponse response = download(collection, recordId, "\"01anoldfileidthatneverexisted\"");

        assertThat(response.getStatusCode(), equalTo(200));
        assertThat(response.getContent(), containsString("fresh-content"));
    }

    @Test
    void replacingTheFileChangesTheEtag() {
        String collection = seed();
        String recordId = upload(collection, "before-replacement");
        String before = download(collection, recordId, null).getHeader("ETag");

        replace(collection, recordId, "after-replacement");
        TestResponse after = download(collection, recordId, null);

        assertThat(after.getContent(), containsString("after-replacement"));
        assertThat(after.getHeader("ETag"), not(equalTo(before)));
        // The old validator must not short-circuit the new content.
        assertThat(download(collection, recordId, before).getStatusCode(), equalTo(200));
    }

    private static String seed() {
        String collection = "docs_cache_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "*", "*", "*", null),
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("attachment", FieldType.FILE, true, false,
                                FieldOptions.forFile(1024 * 1024, List.of("text/plain"), 1))));
        return collection;
    }

    private static String upload(String collection, String content) {
        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withHeader("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .withStringBody(multipart(content))
                .execute();
        assertThat(create.getStatusCode(), equalTo(201));

        Document record = Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), collection)
                .find()
                .first();
        assertThat(record, notNullValue());
        return record.getString("id");
    }

    private static void replace(String collection, String recordId, String content) {
        TestResponse update = TestRequest.patch("/api/collections/" + collection + "/" + recordId)
                .withHeader("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .withStringBody(multipart(content))
                .execute();
        assertThat(update.getStatusCode(), equalTo(200));
    }

    private static TestResponse download(String collection, String recordId, String ifNoneMatch) {
        String path = "/api/collections/" + collection + "/" + recordId + "/files/attachment";
        return ifNoneMatch == null
                ? TestRequest.get(path).execute()
                : TestRequest.get(path).withHeader("If-None-Match", ifNoneMatch).execute();
    }

    private static final String BOUNDARY = "----paprika-test";

    private static String multipart(String content) {
        return "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
                + "hello\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"attachment\"; filename=\"note.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + content + "\r\n"
                + "--" + BOUNDARY + "--\r\n";
    }
}
