package constants;

import org.bson.Document;

import java.time.Instant;
import java.util.Set;

public final class SystemFields {
    public static final String ID = "id";
    public static final String CREATED_AT = "createdAt";
    public static final String UPDATED_AT = "updatedAt";

    private static final Set<String> RESERVED_SCHEMA_NAMES = Set.of(
            ID,
            CREATED_AT,
            UPDATED_AT,
            "created",
            "updated"
    );

    private static final Set<String> READ_ONLY_ON_WRITE = Set.of(
            ID,
            CREATED_AT,
            UPDATED_AT
    );

    private SystemFields() {
    }

    public static boolean isReservedSchemaName(String name) {
        return name != null && RESERVED_SCHEMA_NAMES.contains(name);
    }

    public static boolean isReadOnlyOnWrite(String name) {
        return name != null && READ_ONLY_ON_WRITE.contains(name);
    }

    public static void removeReadOnlyFields(Document document) {
        for (String field : READ_ONLY_ON_WRITE) {
            document.remove(field);
        }
    }

    public static Set<String> indexableFieldNames() {
        return Set.of(ID, CREATED_AT, UPDATED_AT);
    }

    public static String timestamp() {
        return Instant.now().toString();
    }
}
