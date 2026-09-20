package models;

import org.apache.commons.lang3.StringUtils;

/**
 * The rule configuration of one collection.
 * <p>
 * Besides the five operation rules it carries the configuration the non-constant presets need.
 * {@code ownerField} belongs to the {@code owner} preset; the four {@code group*} fields belong to
 * the {@code group} and {@code peers} presets, which decide access through a membership record in
 * a second collection:
 *
 * <pre>
 * groupCollection   crew_members   the collection holding the memberships
 * groupMemberField  user           field in there pointing at the user
 * groupField        crew           field in there pointing at the group
 * groupRecordField  crew           field of *this* collection carrying the group ("group" only)
 * </pre>
 *
 * Like the owner field, this is configuration of the collection rather than part of the rule
 * string: the rule values stay a fixed allowlist.
 */
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
    /**
     * Rules without any membership configuration - the shape every collection had before the
     * {@code group}/{@code peers} presets existed. Kept so that the many call sites that do not
     * care about memberships stay readable.
     */
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

    /**
     * Whether the membership lookup itself is configured. {@code groupRecordField} is not part of
     * this: only the {@code group} preset needs it, {@code peers} matches on the record id.
     */
    public boolean hasMembershipLookup() {
        return StringUtils.isNotBlank(groupCollection)
                && StringUtils.isNotBlank(groupMemberField)
                && StringUtils.isNotBlank(groupField);
    }
}
