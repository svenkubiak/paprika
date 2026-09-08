package services;

import com.fasterxml.jackson.databind.ObjectMapper;
import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.utils.JsonUtils;
import models.CollectionDefinition;
import models.FieldDefinition;
import models.FieldOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.TenantTestUtils;
import validation.ValidationContext;
import validation.ValidationResult;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ExtendWith({TestRunner.class})
class ValidationServiceTest {
    private final ValidationService validationService = Application.getInstance(ValidationService.class);

    @Test
    void rejectsCreateWhenSchemaHasNoFields() throws Exception {
        CollectionDefinition collection = new CollectionDefinition(
                "id-1",
                "empty",
                List.of(),
                List.of(),
                null,
                false
        );

        ValidationResult result = validationService.validateCreate(
                collection,
                JsonUtils.getMapper().readTree("{}")
        );

        assertThat(result.isValid(), is(false));
    }

    @Test
    void rejectsUnknownFieldsOnCreate() throws Exception {
        CollectionDefinition collection = new CollectionDefinition(
                "id-1",
                "posts",
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null)),
                List.of(),
                null,
                false
        );

        ValidationResult result = validationService.validateCreate(
                collection,
                JsonUtils.getMapper().readTree("{\"title\":\"hello\",\"extra\":\"nope\"}")
        );

        assertThat(result.isValid(), is(false));
    }

    @Test
    void acceptsValidCreatePayload() throws Exception {
        CollectionDefinition collection = new CollectionDefinition(
                "id-1",
                "posts",
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null)),
                List.of(),
                null,
                false
        );

        ValidationResult result = validationService.validateCreate(
                collection,
                JsonUtils.getMapper().readTree("{\"title\":\"hello\"}")
        );

        assertThat(result.isValid(), is(true));
    }

    @Test
    void acceptsJsonObjectAndArrayFields() throws Exception {
        CollectionDefinition collection = new CollectionDefinition(
                "id-1",
                "posts",
                List.of(
                        new FieldDefinition("metadata", FieldType.JSON, true, false, null),
                        new FieldDefinition("tags", FieldType.JSON, false, true, null)),
                List.of(),
                null,
                false
        );

        ValidationResult objectResult = validationService.validateCreate(
                collection,
                JsonUtils.getMapper().readTree("{\"metadata\":{\"theme\":\"dark\"},\"tags\":[\"a\",\"b\"]}")
        );

        assertThat(objectResult.isValid(), is(true));
    }

    @Test
    void rejectsInvalidJsonFieldValues() throws Exception {
        CollectionDefinition collection = new CollectionDefinition(
                "id-1",
                "posts",
                List.of(new FieldDefinition("metadata", FieldType.JSON, true, false, null)),
                List.of(),
                null,
                false
        );

        ValidationResult result = validationService.validateCreate(
                collection,
                JsonUtils.getMapper().readTree("{\"metadata\":\"not-json\"}")
        );

        assertThat(result.isValid(), is(false));
    }

    @Test
    void acceptsSelectFieldValues() throws Exception {
        CollectionDefinition collection = new CollectionDefinition(
                "id-1",
                "posts",
                List.of(new FieldDefinition(
                        "status",
                        FieldType.SELECT,
                        true,
                        false,
                        FieldOptions.forSelect(List.of("draft", "published"), 1))),
                List.of(),
                null,
                false
        );

        ValidationResult result = validationService.validateCreate(
                collection,
                JsonUtils.getMapper().readTree("{\"status\":\"draft\"}")
        );

        assertThat(result.isValid(), is(true));
    }

    @Test
    void rejectsInvalidSelectFieldValues() throws Exception {
        CollectionDefinition collection = new CollectionDefinition(
                "id-1",
                "posts",
                List.of(new FieldDefinition(
                        "status",
                        FieldType.SELECT,
                        true,
                        false,
                        FieldOptions.forSelect(List.of("draft", "published"), 1))),
                List.of(),
                null,
                false
        );

        ValidationResult result = validationService.validateCreate(
                collection,
                JsonUtils.getMapper().readTree("{\"status\":\"archived\"}")
        );

        assertThat(result.isValid(), is(false));
    }

    @Test
    void rejectsReadOnlySystemFieldsOnCreate() throws Exception {
        CollectionDefinition collection = new CollectionDefinition(
                "id-1",
                "posts",
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null)),
                List.of(),
                null,
                false
        );

        ValidationResult createdAt = validationService.validateCreate(
                collection,
                JsonUtils.getMapper().readTree("{\"title\":\"hello\",\"createdAt\":\"2026-01-01T00:00:00Z\"}")
        );
        ValidationResult updatedAt = validationService.validateCreate(
                collection,
                JsonUtils.getMapper().readTree("{\"title\":\"hello\",\"updatedAt\":\"2026-01-01T00:00:00Z\"}")
        );

        assertThat(createdAt.isValid(), is(false));
        assertThat(updatedAt.isValid(), is(false));
    }

    @Test
    void rejectsReadOnlySystemFieldsOnUpdate() throws Exception {
        CollectionDefinition collection = new CollectionDefinition(
                "id-1",
                "posts",
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null)),
                List.of(),
                null,
                false
        );

        ValidationResult result = validationService.validateUpdate(
                collection,
                JsonUtils.getMapper().readTree("{\"createdAt\":\"2026-01-01T00:00:00Z\"}")
        );

        assertThat(result.isValid(), is(false));
    }

    @Test
    void rejectsUpdateWhenSchemaHasNoFields() throws Exception {
        CollectionDefinition collection = new CollectionDefinition(
                "id-1",
                "empty",
                List.of(),
                List.of(),
                null,
                false
        );

        ValidationResult result = validationService.validateUpdate(
                collection,
                JsonUtils.getMapper().readTree("{}")
        );

        assertThat(result.isValid(), is(false));
    }

    @Test
    void rejectsRelationWhenTargetRecordDoesNotExist() throws Exception {
        CollectionDefinition authors = new CollectionDefinition(
                "authors-id",
                "relation_missing_authors",
                List.of(new FieldDefinition("name", FieldType.STRING, true, false, null)),
                List.of(),
                null,
                false
        );

        Application.getInstance(TenantCollectionService.class)
                .insertDefinition(TenantTestUtils.defaultTenantContext(), authors);

        CollectionDefinition posts = new CollectionDefinition(
                "posts-id",
                "relation_missing_posts",
                List.of(new FieldDefinition(
                        "author",
                        FieldType.RELATION,
                        true,
                        false,
                        FieldOptions.forRelation("relation_missing_authors"))),
                List.of(),
                null,
                false
        );

        ValidationResult result = validationService.validateCreate(
                posts,
                JsonUtils.getMapper().readTree("{\"author\":\"missing-author-id\"}"),
                ValidationContext.of(TenantTestUtils.defaultTenantContext()));

        assertThat(result.isValid(), is(false));
    }

    @Test
    void acceptsRelationWhenTargetRecordExists() throws Exception {
        Application.getInstance(TenantCollectionService.class)
                .insertDefinition(
                        TenantTestUtils.defaultTenantContext(),
                        new CollectionDefinition(
                                "authors-id",
                                "relation_authors",
                                List.of(new FieldDefinition("name", FieldType.STRING, true, false, null)),
                                List.of(),
                                null,
                                false));

        String authorId = TenantTestUtils.seedRecord("relation_authors", "Ada");

        CollectionDefinition posts = new CollectionDefinition(
                "posts-id",
                "relation_posts",
                List.of(new FieldDefinition(
                        "author",
                        FieldType.RELATION,
                        true,
                        false,
                        FieldOptions.forRelation("relation_authors"))),
                List.of(),
                null,
                false
        );

        Application.getInstance(TenantCollectionService.class)
                .insertDefinition(TenantTestUtils.defaultTenantContext(), posts);

        ValidationResult result = validationService.validateCreate(
                posts,
                JsonUtils.getMapper().readTree("{\"author\":\"" + authorId + "\"}"),
                ValidationContext.of(TenantTestUtils.defaultTenantContext()));

        assertThat(result.isValid(), is(true));
    }

    @Test
    void rejectsStringShorterThanMinLength() throws Exception {
        CollectionDefinition collection = new CollectionDefinition(
                "id-1",
                "posts",
                List.of(new FieldDefinition(
                        "code",
                        FieldType.STRING,
                        true,
                        false,
                        FieldOptions.forString(3, null, null))),
                List.of(),
                null,
                false
        );

        ValidationResult result = validationService.validateCreate(
                collection,
                JsonUtils.getMapper().readTree("{\"code\":\"ab\"}")
        );

        assertThat(result.isValid(), is(false));
    }

    @Test
    void acceptsFloatingPointNumbers() throws Exception {
        CollectionDefinition collection = new CollectionDefinition(
                "id-1",
                "products",
                List.of(new FieldDefinition(
                        "price",
                        FieldType.NUMBER,
                        true,
                        false,
                        FieldOptions.forNumber(0.0, 100.0))),
                List.of(),
                null,
                false
        );

        ValidationResult result = validationService.validateCreate(
                collection,
                JsonUtils.getMapper().readTree("{\"price\":12.99}")
        );

        assertThat(result.isValid(), is(true));
    }

    @Test
    void rejectsNumberOutsideConfiguredRange() throws Exception {
        CollectionDefinition collection = new CollectionDefinition(
                "id-1",
                "products",
                List.of(new FieldDefinition(
                        "price",
                        FieldType.NUMBER,
                        true,
                        false,
                        FieldOptions.forNumber(0.0, 10.0))),
                List.of(),
                null,
                false
        );

        ValidationResult result = validationService.validateCreate(
                collection,
                JsonUtils.getMapper().readTree("{\"price\":12.5}")
        );

        assertThat(result.isValid(), is(false));
    }
}
