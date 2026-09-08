package validation.validators;

import auth.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import enums.FieldType;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.CollectionDefinition;
import models.FieldDefinition;
import models.FieldOptions;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import services.TenantCollectionService;
import validation.ValidationContext;
import validation.ValidationResult;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static com.mongodb.client.model.Filters.eq;

@Singleton
public class RelationFieldValidator implements FieldValidator {
    private final TenantCollectionService tenantCollections;

    @Inject
    public RelationFieldValidator(TenantCollectionService tenantCollections) {
        this.tenantCollections = Objects.requireNonNull(tenantCollections, "tenantCollections must not be null");
    }

    @Override
    public FieldType supports() {
        return FieldType.RELATION;
    }

    @Override
    public void validate(
            FieldDefinition field,
            JsonNode value,
            ValidationResult result,
            ValidationContext context) {

        FieldOptions options = field.optionsOrDefault();
        if (options.maxSelectOrDefault() <= 1) {
            validateSingle(field, value, result, context);
            return;
        }

        validateMultiple(field, value, options, result, context);
    }

    private void validateSingle(
            FieldDefinition field,
            JsonNode value,
            ValidationResult result,
            ValidationContext context) {

        if (!value.isTextual()) {
            result.add(field.name(), "Expected relation id string");
            return;
        }

        validateRelationId(field, value.asText(), result, context);
    }

    private void validateMultiple(
            FieldDefinition field,
            JsonNode value,
            FieldOptions options,
            ValidationResult result,
            ValidationContext context) {

        if (!value.isArray()) {
            result.add(field.name(), "Expected array of relation ids");
            return;
        }

        if (value.size() > options.maxSelectOrDefault()) {
            result.add(field.name(), "Too many relation ids");
            return;
        }

        Set<String> seen = new HashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                result.add(field.name(), "Expected array of relation ids");
                return;
            }
            String relatedId = item.asText();
            if (!seen.add(relatedId)) {
                result.add(field.name(), "Duplicate relation id");
                return;
            }
            validateRelationId(field, relatedId, result, context);
            if (!result.isValid()) {
                return;
            }
        }
    }

    private void validateRelationId(
            FieldDefinition field,
            String relatedId,
            ValidationResult result,
            ValidationContext context) {

        if (StringUtils.isBlank(relatedId)) {
            result.add(field.name(), "Expected relation id string");
            return;
        }

        if (field.options() == null || StringUtils.isBlank(field.options().collection())) {
            result.add(field.name(), "Relation target collection is not configured");
            return;
        }

        TenantContext tenantContext = context.tenantContext();
        if (tenantContext == null || !tenantContext.hasTenantContext()) {
            result.add(field.name(), "Relation validation requires tenant context");
            return;
        }

        String targetCollection = field.options().collection().trim();
        CollectionDefinition targetDefinition = tenantCollections.findDefinition(tenantContext, targetCollection);
        if (targetDefinition == null) {
            result.add(field.name(), "Related collection not found: " + targetCollection);
            return;
        }

        Document relatedRecord = tenantCollections.dataCollection(tenantContext, targetCollection)
                .find(eq("id", relatedId))
                .first();

        if (relatedRecord == null) {
            result.add(field.name(), "Related record not found in " + targetCollection);
        }
    }
}
