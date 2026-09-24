package validation;

import java.util.regex.Pattern;

/**
 * The characters a collection or field name may consist of.
 * <p>
 * Names are not labels: a collection name becomes a MongoDB collection, a field name becomes a
 * key in the documents of that collection and in the {@code $set} of every update. Two
 * characters have their own meaning there. A dot addresses a nested path, so a field called
 * {@code a.b} does not write a field of that name but into a subdocument - and a rule or an
 * index that names {@code a.b} means something else again. A dollar sign starts an operator,
 * which MongoDB refuses with an error the caller sees as a 500. A name starting with an
 * underscore is out for a third reason: {@code _id} is MongoDB's own primary key, and a field
 * of that name collides with it on every write.
 * <p>
 * Checked in one place because there are two ways a schema is written - the meta API and the
 * schema import - and a rule that only one of them enforces is not a rule. Both go through
 * {@code TenantCollectionService#validateDefinition}.
 */
public final class SchemaNames {
    public static final int MAX_LENGTH = 64;

    /**
     * Letters, digits, underscores and hyphens, starting with a letter. Deliberately an
     * allowlist: the set of characters MongoDB, JSON and the query parameters of the list API
     * all handle without a special meaning is small and easy to name, the set of characters
     * that go wrong somewhere is not.
     */
    private static final Pattern VALID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    private SchemaNames() {
    }

    public static void requireValidCollectionName(String name) {
        require(name, "Collection name");
    }

    public static void requireValidFieldName(String name) {
        require(name, "Field name");
    }

    private static void require(String name, String subject) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(subject + " must not be empty");
        }

        if (!VALID.matcher(name).matches()) {
            throw new IllegalArgumentException(subject + " \"" + name + "\" is invalid: use letters, digits, "
                    + "underscores and hyphens only, start with a letter, and stay under " + MAX_LENGTH
                    + " characters");
        }
    }
}
