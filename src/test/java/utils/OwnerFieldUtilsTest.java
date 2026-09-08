package utils;

import auth.AuthContext;
import constants.SystemCollections;
import enums.FieldType;
import models.CollectionDefinition;
import models.CollectionRules;
import models.FieldDefinition;
import models.FieldOptions;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

class OwnerFieldUtilsTest {

    @Test
    void appliesAuthenticatedUserAsOwnerOnCreate() {
        CollectionDefinition definition = collectionWithOwnerRelation();
        Document document = new Document("title", "hello");
        AuthContext auth = AuthContext.of("user-1", "user", "tenant-1");

        OwnerFieldUtils.applyOwnerOnCreate(document, definition, auth);

        assertThat(document.getString("owner"), equalTo("user-1"));
    }

    @Test
    void rejectsSpoofedOwnerOnCreate() {
        CollectionDefinition definition = collectionWithOwnerRelation();
        Document document = new Document("title", "hello").append("owner", "other-user");
        AuthContext auth = AuthContext.of("user-1", "user", "tenant-1");

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> OwnerFieldUtils.applyOwnerOnCreate(document, definition, auth));
    }

    @Test
    void defaultOwnerFieldPrefersUsersRelationField() {
        CollectionDefinition definition = collectionWithOwnerRelation();

        assertThat(OwnerFieldUtils.defaultOwnerField(definition), equalTo("owner"));
    }

    @Test
    void doesNotAssignOwnerWhenSchemaFieldMissing() {
        CollectionDefinition definition = new CollectionDefinition(
                "id",
                "posts",
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null)),
                List.of(),
                new CollectionRules("owner", "owner", "auth", "owner", "owner", "owner"),
                false
        );

        Document document = new Document("title", "hello");
        OwnerFieldUtils.applyOwnerOnCreate(document, definition, AuthContext.of("user-1", "user", "tenant-1"));

        assertThat(document.get("owner"), nullValue());
    }

    private CollectionDefinition collectionWithOwnerRelation() {
        return new CollectionDefinition(
                "id",
                "notes",
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition(
                                "owner",
                                FieldType.RELATION,
                                false,
                                true,
                                FieldOptions.forRelation(SystemCollections.USERS))
                ),
                List.of(),
                new CollectionRules("owner", "owner", "auth", "owner", "owner", "owner"),
                false
        );
    }
}
