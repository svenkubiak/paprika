package rules;

import constants.SystemFields;
import enums.FieldType;
import models.CollectionDefinition;
import models.FieldDefinition;
import utils.UserRecordUtils;

import java.util.Optional;

/**
 * The fields a list may filter and sort by: schema fields plus id, createdAt and updatedAt. Shared
 * by ListFilterParser and ListSortParser, which add their own type restrictions on top.
 */
final class ListFields {

    static final class UnusableFieldException extends RuntimeException {
        UnusableFieldException(String message) {
            super(message);
        }
    }

    private ListFields() {
    }

    static FieldType resolve(String field, CollectionDefinition definition) {
        if (field.isEmpty()) {
            throw new UnusableFieldException("field must not be empty");
        }

        // A field that is never returned must not be probed either: a filter on it would reveal
        // its value record by record. password is a write-only schema field of users.
        if (isNeverReturned(field, definition)) {
            throw new UnusableFieldException("field '" + field + "' is never returned and cannot be used");
        }

        // createdAt/updatedAt are stored like DATETIME values, so they are filtered and sorted like them
        if (SystemFields.indexableFieldNames().contains(field)) {
            return SystemFields.ID.equals(field) ? FieldType.STRING : FieldType.DATETIME;
        }

        return schemaField(field, definition)
                .map(FieldDefinition::type)
                // A silently ignored condition would return too many records unnoticed
                .orElseThrow(() -> new UnusableFieldException("unknown field '" + field + "'"));
    }

    static Optional<FieldDefinition> schemaField(String field, CollectionDefinition definition) {
        if (definition == null || definition.fields() == null) {
            return Optional.empty();
        }
        return definition.fields().stream()
                .filter(candidate -> field.equals(candidate.name()))
                .findFirst();
    }

    static boolean isNeverReturned(String field, CollectionDefinition definition) {
        return definition != null
                && UserRecordUtils.isUsers(definition.name())
                && (UserRecordUtils.PASSWORD.equals(field) || UserRecordUtils.CREDENTIAL_FIELDS.contains(field));
    }
}
