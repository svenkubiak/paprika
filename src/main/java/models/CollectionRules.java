package models;

import org.apache.commons.lang3.StringUtils;

// The group* fields configure the group/peers presets via a membership collection; keeping them out
// of the rule strings keeps those a fixed allowlist. groupRecordField "id" means the records are the
// groups themselves (no create then, see RuleService).
public record CollectionRules(
        String listRule,
        String viewRule,
        String createRule,
        String updateRule,
        String deleteRule,
        String ownerField,
        String groupCollection,
        String groupMemberField,
        String groupField,
        String groupRecordField
) {
    public CollectionRules(
            String listRule,
            String viewRule,
            String createRule,
            String updateRule,
            String deleteRule,
            String ownerField) {
        this(listRule, viewRule, createRule, updateRule, deleteRule, ownerField, null, null, null, null);
    }

    public static CollectionRules locked() {
        return new CollectionRules(null, null, null, null, null, "owner");
    }

    public String ownerFieldOrDefault() {
        return ownerField != null && !ownerField.isBlank() ? ownerField : "owner";
    }

    // groupRecordField is excluded: only the group preset needs it, peers matches on the record id.
    public boolean hasMembershipLookup() {
        return StringUtils.isNotBlank(groupCollection)
                && StringUtils.isNotBlank(groupMemberField)
                && StringUtils.isNotBlank(groupField);
    }
}
