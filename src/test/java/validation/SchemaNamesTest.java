package validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The character set of collection and field names. What is rejected here is rejected on both
 * ways into a schema - the meta API and the schema import - because both run through
 * {@code TenantCollectionService#validateDefinition}.
 */
class SchemaNamesTest {

    @Test
    void ordinaryNamesAreAccepted() {
        assertDoesNotThrow(() -> SchemaNames.requireValidFieldName("title"));
        assertDoesNotThrow(() -> SchemaNames.requireValidFieldName("firstName"));
        assertDoesNotThrow(() -> SchemaNames.requireValidFieldName("line_1"));
        assertDoesNotThrow(() -> SchemaNames.requireValidFieldName("first-name"));
        assertDoesNotThrow(() -> SchemaNames.requireValidCollectionName("posts"));
        assertDoesNotThrow(() -> SchemaNames.requireValidCollectionName("crew_members"));
        // The suffix the tests generate: a collection name carries hyphens in practice
        assertDoesNotThrow(() -> SchemaNames.requireValidCollectionName("posts_01a0d25c-5cb2-7292-9a6f"));
    }

    @Test
    void mongoSyntaxIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SchemaNames.requireValidFieldName("author.name"));
        assertThrows(IllegalArgumentException.class, () -> SchemaNames.requireValidFieldName("$set"));
        assertThrows(IllegalArgumentException.class, () -> SchemaNames.requireValidFieldName("pri$ce"));
        assertThrows(IllegalArgumentException.class, () -> SchemaNames.requireValidCollectionName("meta.collections"));
    }

    @Test
    void aLeadingUnderscoreOrDigitIsRejected() {
        // _id is MongoDB's primary key, and a collection name is prefixed with "_" internally
        assertThrows(IllegalArgumentException.class, () -> SchemaNames.requireValidFieldName("_id"));
        assertThrows(IllegalArgumentException.class, () -> SchemaNames.requireValidFieldName("_internal"));
        assertThrows(IllegalArgumentException.class, () -> SchemaNames.requireValidFieldName("2fa"));
        assertThrows(IllegalArgumentException.class, () -> SchemaNames.requireValidCollectionName("_posts"));
    }

    @Test
    void whitespaceAndEmptyNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SchemaNames.requireValidFieldName(null));
        assertThrows(IllegalArgumentException.class, () -> SchemaNames.requireValidFieldName(""));
        assertThrows(IllegalArgumentException.class, () -> SchemaNames.requireValidFieldName("  "));
        assertThrows(IllegalArgumentException.class, () -> SchemaNames.requireValidFieldName("first name"));
        assertThrows(IllegalArgumentException.class, () -> SchemaNames.requireValidFieldName("title\n"));
    }

    @Test
    void anOverlongNameIsRejected() {
        assertDoesNotThrow(() -> SchemaNames.requireValidFieldName("a".repeat(SchemaNames.MAX_LENGTH)));
        assertThrows(IllegalArgumentException.class,
                () -> SchemaNames.requireValidFieldName("a".repeat(SchemaNames.MAX_LENGTH + 1)));
    }
}
