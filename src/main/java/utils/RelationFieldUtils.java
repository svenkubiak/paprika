package utils;

import auth.TenantContext;
import enums.FieldType;
import models.CollectionDefinition;
import models.FieldDefinition;
import models.FieldOptions;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import services.TenantCollectionService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.mongodb.client.model.Filters.eq;

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

    public static void cascadeDeleteRelatedRecords(
            TenantContext ctx,
            TenantCollectionService tenantCollections,
            CollectionDefinition definition,
            Document record) {

        for (FieldDefinition field : relationFields(definition)) {
            if (!field.optionsOrDefault().cascadeDeleteOrDefault()) {
                continue;
            }
            String targetCollection = field.optionsOrDefault().collection();
            if (StringUtils.isBlank(targetCollection)) {
                continue;
            }
            for (String relatedId : relationIds(record.get(field.name()), field)) {
                tenantCollections.dataCollection(ctx, targetCollection.trim()).deleteOne(eq("id", relatedId));
            }
        }
    }

    public static Set<String> uniqueRelationIds(Object value, FieldDefinition field) {
        return new LinkedHashSet<>(relationIds(value, field));
    }
}
