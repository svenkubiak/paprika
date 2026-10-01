package validation;

import java.util.regex.Pattern;

// Names become MongoDB collections and document keys: '.' addresses a nested path, '$' starts an
// operator, and a leading '_' collides with _id. Meta API and schema import must both check here.
public final class SchemaNames {
    public static final int MAX_LENGTH = 64;

    // Deliberately an allowlist: the characters that go wrong somewhere are not easy to enumerate.
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
