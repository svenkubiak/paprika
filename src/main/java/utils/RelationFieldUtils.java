package utils;

import enums.FieldType;
import models.CollectionDefinition;
import models.FieldDefinition;
import models.FieldOptions;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RelationFieldUtils {
    private RelationFieldUtils() {
    }

    public static List<FieldDefinition> relationFields(CollectionDefinition definition) {
        if (definition.fields() == null) {
            return List.of();
        }
        return definition.fields().stream()
                .filter(field -> field.type() == FieldType.RELATION)
                .toList();
    }

    public static List<String> relationIds(Object value, FieldDefinition field) {
        if (value == null) {
            return List.of();
        }
        FieldOptions options = field.optionsOrDefault();
        if (options.maxSelectOrDefault() <= 1) {
            String id = String.valueOf(value);
            return StringUtils.isBlank(id) ? List.of() : List.of(id);
        }
        if (value instanceof List<?> list) {
            List<String> ids = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !StringUtils.isBlank(String.valueOf(item))) {
                    ids.add(String.valueOf(item));
                }
            }
            return ids;
        }
        return List.of();
    }

    /**
     * The cascading delete itself lives in {@code services.RelationCascadeService}, not here: its
     * target is a client-chosen record in another collection and must be authorized against that
     * collection's delete rule before it is removed. A static helper with a MongoDB handle and no
     * idea who is calling cannot do that, which is exactly how this used to delete other people's
     * records.
     */
    public static Set<String> uniqueRelationIds(Object value, FieldDefinition field) {
        return new LinkedHashSet<>(relationIds(value, field));
    }
}
