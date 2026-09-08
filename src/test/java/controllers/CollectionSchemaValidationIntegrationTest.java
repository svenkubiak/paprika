package controllers;

import enums.FieldType;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionRules;
import models.FieldDefinition;
import models.FieldOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@ExtendWith({TestRunner.class})
class CollectionSchemaValidationIntegrationTest {

    @Test
    void createWithoutSchemaFieldsReturnsBadRequest() {
        String collection = "empty_schema_create_" + DbUtils.id();
        TenantTestUtils.seedCollection(collection, new CollectionRules("*", "*", "*", "*", "*", "owner"), List.of());

        TestResponse response = TestRequest.post("/api/collections/" + collection)
                .withStringBody("{\"anything\":\"value\"}")
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("Collection has no schema fields defined"));
    }

    @Test
    void updateWithoutSchemaFieldsReturnsBadRequest() {
        String collection = "empty_schema_update_" + DbUtils.id();
        TenantTestUtils.seedCollection(collection, new CollectionRules("*", "*", "*", "*", "*", "owner"), List.of());
        String recordId = TenantTestUtils.seedRecord(collection, "legacy");

        TestResponse response = TestRequest.patch("/api/collections/" + collection + "/" + recordId)
                .withStringBody("{}")
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("Collection has no schema fields defined"));
    }

    @Test
    void createWithMissingRelationTargetReturnsBadRequest() {
        String authors = "relation_api_authors_" + DbUtils.id();
        String posts = "relation_api_posts_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                authors,
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                List.of(new FieldDefinition("name", FieldType.STRING, true, false, null)));
        TenantTestUtils.seedCollection(
                posts,
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                List.of(new FieldDefinition(
                        "author",
                        FieldType.RELATION,
                        true,
                        false,
                        FieldOptions.forRelation(authors))));

        TestResponse response = TestRequest.post("/api/collections/" + posts)
                .withStringBody("{\"author\":\"missing-author-id\"}")
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("Related record not found"));
    }

    @Test
    void createWithValidRelationTargetSucceeds() {
        String authors = "relation_api_ok_authors_" + DbUtils.id();
        String posts = "relation_api_ok_posts_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                authors,
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                List.of(new FieldDefinition("name", FieldType.STRING, true, false, null)));
        String authorId = TenantTestUtils.seedRecord(authors, "Ada");
        TenantTestUtils.seedCollection(
                posts,
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                List.of(new FieldDefinition(
                        "author",
                        FieldType.RELATION,
                        true,
                        false,
                        FieldOptions.forRelation(authors))));

        TestResponse response = TestRequest.post("/api/collections/" + posts)
                .withStringBody("{\"author\":\"" + authorId + "\"}")
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.CREATED));
    }
}
