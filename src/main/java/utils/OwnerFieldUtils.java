package utils;

import auth.AuthContext;
import constants.SystemCollections;
import enums.FieldType;
import models.CollectionDefinition;
import models.CollectionRules;
import models.FieldDefinition;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class OwnerFieldUtils {
    private OwnerFieldUtils() {
    }

    public static boolean isUsersRelationField(FieldDefinition field) {
        return field != null
                && field.type() == FieldType.RELATION
                && field.options() != null
                && SystemCollections.USERS.equals(field.options().collection());
    }

    public static List<String> ownerFieldCandidates(CollectionDefinition definition) {
        if (definition == null || definition.fields() == null) {
            return List.of();
        }

        List<String> candidates = new ArrayList<>();
        for (FieldDefinition field : definition.fields()) {
            if (isUsersRelationField(field)) {
                candidates.add(field.name());
            }
        }
        return candidates;
    }

    public static String defaultOwnerField(CollectionDefinition definition) {
        List<String> candidates = ownerFieldCandidates(definition);
        if (!candidates.isEmpty()) {
            return candidates.getFirst();
        }
        return "owner";
    }

    public static boolean usesOwnerRule(CollectionRules rules) {
        if (rules == null) {
            return false;
        }
        return isOwnerRule(rules.listRule())
                || isOwnerRule(rules.viewRule())
                || isOwnerRule(rules.createRule())
                || isOwnerRule(rules.updateRule())
                || isOwnerRule(rules.deleteRule());
    }

    public static boolean shouldAssignOwnerOnCreate(CollectionDefinition definition, AuthContext auth) {
        if (!auth.isAuthenticated() || definition == null) {
            return false;
        }

        CollectionRules rules = definition.rulesOrDefault();
        String ownerField = rules.ownerFieldOrDefault();
        if (!hasSchemaField(definition, ownerField)) {
            return false;
        }

        return usesOwnerRule(rules) || "auth".equalsIgnoreCase(StringUtils.trimToEmpty(rules.createRule()));
    }

    public static void applyOwnerOnCreate(Document document, CollectionDefinition definition, AuthContext auth) {
        if (!shouldAssignOwnerOnCreate(definition, auth)) {
            return;
        }

        String ownerField = definition.rulesOrDefault().ownerFieldOrDefault();
        Object existing = document.get(ownerField);
        if (existing == null || StringUtils.isBlank(String.valueOf(existing))) {
            document.put(ownerField, auth.id());
            return;
        }

        if (!Objects.equals(String.valueOf(existing), auth.id())) {
            throw new IllegalArgumentException("Owner field must match the authenticated user");
        }
    }

    public static Map<String, Object> effectiveCreateBody(
            String rule,
            String ownerField,
            AuthContext auth,
            Map<String, Object> body) {

        if (!isOwnerRule(rule) || !auth.isAuthenticated()) {
            return body != null ? body : Map.of();
        }

        java.util.HashMap<String, Object> effective = body != null
                ? new java.util.HashMap<>(body)
                : new java.util.HashMap<>();

        Object provided = effective.get(ownerField);
        if (provided == null || StringUtils.isBlank(String.valueOf(provided))) {
            effective.put(ownerField, auth.id());
        } else if (!Objects.equals(String.valueOf(provided), auth.id())) {
            return Map.of();
        }

        return effective;
    }

    public static Document effectiveCreateRecord(Map<String, Object> body) {
        return body == null || body.isEmpty() ? null : new Document(body);
    }

    private static boolean hasSchemaField(CollectionDefinition definition, String fieldName) {
        if (definition.fields() == null) {
            return false;
        }
        return definition.fields().stream().anyMatch(field -> fieldName.equals(field.name()));
    }

    private static boolean isOwnerRule(String rule) {
        return rule != null && "owner".equalsIgnoreCase(rule.trim());
    }
}
