package services;

import com.fasterxml.jackson.databind.JsonNode;
import enums.FieldType;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.CollectionDefinition;
import models.FieldDefinition;
import constants.SystemFields;
import validation.ValidationContext;
import validation.ValidationResult;
import validation.validators.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Singleton
public class ValidationService {
    private final Map<FieldType, FieldValidator> validators;

    @Inject
    public ValidationService(RelationFieldValidator relationFieldValidator) {
        List<FieldValidator> fieldValidators = List.of(
                new NumberFieldValidator(),
                new StringFieldValidator(),
                new BooleanFieldValidator(),
                new DateFieldValidator(),
                new DateTimeFieldValidator(),
                new EmailFieldValidator(),
                new TimeFieldValidator(),
                new UrlFieldValidator(),
                relationFieldValidator,
                new FileFieldValidator(),
                new JsonFieldValidator(),
                new SelectFieldValidator()
        );

        this.validators = fieldValidators.stream()
                .collect(Collectors.toMap(
                        FieldValidator::supports,
                        Function.identity()
                ));
    }

    public ValidationResult validateCreate(CollectionDefinition collection, JsonNode document) {
        return validateCreate(collection, document, ValidationContext.empty());
    }

    public ValidationResult validateCreate(
            CollectionDefinition collection,
            JsonNode document,
            ValidationContext context) {
        ValidationResult result = new ValidationResult();

        if (!document.isObject()) {
            result.add(null, "Expected JSON object");
            return result;
        }

        List<FieldDefinition> schemaFields = collection.fields() != null ? collection.fields() : List.of();

        if (schemaFields.isEmpty()) {
            result.add(null, "Collection has no schema fields defined");
            return result;
        }

        Set<String> allowedFields = schemaFields.stream()
                .map(FieldDefinition::name)
                .collect(Collectors.toSet());

        document.properties().forEach(entry -> {
            String fieldName = entry.getKey();

            if (SystemFields.isReadOnlyOnWrite(fieldName)) {
                result.add(fieldName, "Field is read-only");
                return;
            }

            if (!allowedFields.contains(fieldName)) {
                result.add(fieldName, "Unknown field");
            }
        });

        for (FieldDefinition field : schemaFields) {

            JsonNode value = document.get(field.name());

            if (value == null || value.isNull()) {
                if (field.required() && field.type() != enums.FieldType.FILE) {
                    result.add(
                            field.name(),
                            "Field is required"
                    );
                }

                continue;
            }

            validateField(field, value, result, context);
        }

        return result;
    }

    public ValidationResult validateUpdate(
            CollectionDefinition collection,
            JsonNode document) {
        return validateUpdate(collection, document, ValidationContext.empty());
    }

    public ValidationResult validateUpdate(
            CollectionDefinition collection,
            JsonNode document,
            ValidationContext context) {

        ValidationResult result = new ValidationResult();

        if (!document.isObject()) {
            result.add(null, "Expected JSON object");
            return result;
        }

        List<FieldDefinition> schemaFields = collection.fields() != null ? collection.fields() : List.of();

        if (schemaFields.isEmpty()) {
            result.add(null, "Collection has no schema fields defined");
            return result;
        }

        document.properties().forEach(entry -> {

            String fieldName = entry.getKey();
            JsonNode value = entry.getValue();

            if (SystemFields.isReadOnlyOnWrite(fieldName)) {
                result.add(fieldName, "Field is read-only");
                return;
            }

            if (collection.fields() == null) {
                result.add(fieldName, "Unknown field");
                return;
            }

            FieldDefinition field = collection.fields().stream()
                    .filter(it -> it.name().equals(fieldName))
                    .findFirst()
                    .orElse(null);

            if (field == null) {
                result.add(fieldName, "Unknown field");
                return;
            }

            if (value == null || value.isNull()) {
                return;
            }

            validateField(field, value, result, context);
        });

        return result;
    }

    private void validateField(
            FieldDefinition field,
            JsonNode value,
            ValidationResult result,
            ValidationContext context) {

        FieldValidator validator = validators.get(field.type());

        if (validator == null) {
            result.add(
                    field.name(),
                    "No validator found for type " + field.type()
            );
            return;
        }

        validator.validate(field, value, result, context);
    }
}
