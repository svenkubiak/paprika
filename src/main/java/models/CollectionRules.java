package models;

public record CollectionRules(
        String listRule,
        String viewRule,
        String createRule,
        String updateRule,
        String deleteRule,
        String ownerField
) {
    public static CollectionRules locked() {
        return new CollectionRules(null, null, null, null, null, "owner");
    }

    public String ownerFieldOrDefault() {
        return ownerField != null && !ownerField.isBlank() ? ownerField : "owner";
    }
}
