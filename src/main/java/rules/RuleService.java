package rules;

import auth.AuthContext;
import auth.TenantContext;
import com.mongodb.client.model.Filters;
import constants.SystemFields;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.CollectionRules;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.bson.conversions.Bson;
import rules.ast.RuleNode;
import utils.OwnerFieldUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Singleton
public final class RuleService {
    public static final String RULE_GROUP = "group";
    public static final String RULE_PEERS = "peers";

    private final MembershipResolver membershipResolver;

    @Inject
    public RuleService(MembershipResolver membershipResolver) {
        this.membershipResolver = Objects.requireNonNull(membershipResolver, "membershipResolver must not be null");
    }

    // Membership presets always deny in this instance: there is no database for the lookup
    public RuleService() {
        this(MembershipResolver.denying());
    }

    public RuleMode resolveMode(String rule) {
        if (rule == null || rule.isBlank()) {
            return RuleMode.LOCKED;
        }
        if ("*".equals(rule.trim())) {
            return RuleMode.PUBLIC;
        }
        return RuleMode.EXPRESSION;
    }

    // For collections whose records point at their owner, i.e. every collection but users
    public String normalizeRule(String rule, String ownerField) {
        return normalizeRule(rule, ownerField, null);
    }

    public String normalizeRule(String rule, String ownerField, String collection) {
        if (rule == null || rule.isBlank()) {
            return null;
        }
        String trimmed = rule.trim();
        if ("*".equals(trimmed)) {
            return "*";
        }
        if ("auth".equalsIgnoreCase(trimmed)) {
            return "auth.id != null";
        }
        if ("owner".equalsIgnoreCase(trimmed)) {
            // On the users collection the own record is the caller's account, not a relation to it
            return OwnerFieldUtils.isSelfOwnedCollection(collection)
                    ? "record.id = auth.id"
                    : "record." + ownerField + " = auth.id";
        }
        return trimmed;
    }

    // These presets never reach RuleParser: the rule language cannot express a subquery
    public static boolean isMembershipRule(String rule) {
        return isGroupRule(rule) || isPeersRule(rule);
    }

    public static boolean isGroupRule(String rule) {
        return rule != null && RULE_GROUP.equalsIgnoreCase(rule.trim());
    }

    public static boolean isPeersRule(String rule) {
        return rule != null && RULE_PEERS.equalsIgnoreCase(rule.trim());
    }

    public void validateRule(String rule, String ownerField) {
        if (rule == null || rule.isBlank()) {
            return;
        }

        String trimmed = rule.trim();
        if ("*".equals(trimmed)) {
            return;
        }

        if ("auth".equalsIgnoreCase(trimmed) || "owner".equalsIgnoreCase(trimmed)) {
            RuleParser.parse(normalizeRule(trimmed, ownerField));
            return;
        }

        if (isMembershipRule(trimmed)) {
            return;
        }

        throw new RuleParseException(
                "Unsupported rule: " + rule + ". Allowed values: (empty), *, auth, owner, group, peers."
        );
    }

    public void validateRules(CollectionRules rules) {
        validateRules(rules, null);
    }

    // An incomplete group/peers configuration fails the save rather than silently locking at
    // request time. Existence of the referenced collection/fields is checked in TenantCollectionService.
    public void validateRules(CollectionRules rules, String collection) {
        if (rules == null) {
            return;
        }
        String ownerField = rules.ownerFieldOrDefault();
        validateRule(rules.listRule(), ownerField);
        validateRule(rules.viewRule(), ownerField);
        validateRule(rules.createRule(), ownerField);
        validateRule(rules.updateRule(), ownerField);
        validateRule(rules.deleteRule(), ownerField);

        validateMembershipConfiguration(rules, collection);
    }

    private void validateMembershipConfiguration(CollectionRules rules, String collection) {
        List<String> all = List.of(
                StringUtils.trimToEmpty(rules.listRule()),
                StringUtils.trimToEmpty(rules.viewRule()),
                StringUtils.trimToEmpty(rules.createRule()),
                StringUtils.trimToEmpty(rules.updateRule()),
                StringUtils.trimToEmpty(rules.deleteRule()));

        boolean usesGroup = all.stream().anyMatch(RuleService::isGroupRule);
        boolean usesPeers = all.stream().anyMatch(RuleService::isPeersRule);

        if (!usesGroup && !usesPeers) {
            return;
        }

        if (collection != null) {
            // "peers" compares the record id against member ids, so it only fits the users collection
            if (usesPeers && !OwnerFieldUtils.isSelfOwnedCollection(collection)) {
                throw new RuleParseException(
                        "The \"peers\" rule is only available on the users collection; use \"group\" here.");
            }
            if (usesGroup && OwnerFieldUtils.isSelfOwnedCollection(collection)) {
                throw new RuleParseException(
                        "The \"group\" rule is not available on the users collection; use \"peers\" here.");
            }
        }

        if (StringUtils.isBlank(rules.groupCollection())) {
            throw new RuleParseException(missing("groupCollection", "the collection holding the memberships"));
        }
        if (StringUtils.isBlank(rules.groupMemberField())) {
            throw new RuleParseException(missing("groupMemberField",
                    "the field of " + rules.groupCollection() + " pointing at the user"));
        }
        if (StringUtils.isBlank(rules.groupField())) {
            throw new RuleParseException(missing("groupField",
                    "the field of " + rules.groupCollection() + " pointing at the group"));
        }
        if (usesGroup && StringUtils.isBlank(rules.groupRecordField())) {
            throw new RuleParseException(missing("groupRecordField",
                    "the field of this collection carrying the group"));
        }

        // With groupRecordField "id" the record is the group itself, so a "group" create rule could
        // never be satisfied ("id" is read-only on write); refuse the save instead of always denying.
        if (isGroupRule(rules.createRule())
                && SystemFields.ID.equals(StringUtils.trimToEmpty(rules.groupRecordField()))) {
            throw new RuleParseException(
                    "A \"group\" create rule cannot be combined with groupRecordField \"" + SystemFields.ID
                            + "\": the record is the group itself, so no caller could ever be a member of it "
                            + "beforehand and \"" + SystemFields.ID + "\" is read-only on write. "
                            + "Use \"auth\" or \"owner\" for create.");
        }
    }

    private static String missing(String field, String description) {
        return "A \"group\" or \"peers\" rule requires \"" + field + "\": " + description + ".";
    }

    public String ruleFor(CollectionRules rules, RuleOperation operation) {
        if (rules == null) {
            return null;
        }
        return switch (operation) {
            case LIST -> rules.listRule();
            case VIEW -> rules.viewRule();
            case CREATE -> rules.createRule();
            case UPDATE -> rules.updateRule();
            case DELETE -> rules.deleteRule();
        };
    }

    public boolean canAccess(
            String rule,
            String ownerField,
            AuthContext auth,
            Document record,
            Map<String, Object> body) {

        return canAccess(rule, ownerField, auth, record, body, null);
    }

    public boolean canAccess(
            String rule,
            String ownerField,
            AuthContext auth,
            Document record,
            Map<String, Object> body,
            String collection) {

        RuleMode mode = resolveMode(rule);
        if (mode == RuleMode.LOCKED) {
            return false;
        }
        if (mode == RuleMode.PUBLIC) {
            return true;
        }

        // No tenant context or rules here, so the membership lookup cannot run: deny
        if (isMembershipRule(rule)) {
            return false;
        }

        // Decided here so a client-supplied own id in the body cannot grant inserting a second
        // record under that identity. Sign-up goes through POST /api/auth/register.
        if (record == null && isOwnerRule(rule) && OwnerFieldUtils.isSelfOwnedCollection(collection)) {
            return false;
        }

        Map<String, Object> effectiveBody = OwnerFieldUtils.effectiveCreateBody(rule, ownerField, auth, body);
        if (effectiveBody.isEmpty() && isOwnerRule(rule) && auth.isAuthenticated()) {
            return false;
        }

        Document effectiveRecord = record != null
                ? record
                : OwnerFieldUtils.effectiveCreateRecord(effectiveBody);

        String normalized = normalizeRule(rule, ownerField, collection);
        RuleNode node = RuleParser.parse(normalized);
        return RuleEvaluator.evaluate(node, RuleEvaluationContext.of(auth, effectiveRecord, effectiveBody));
    }

    // Used by both the data-plane auth filter and realtime delivery, so a client gets nothing
    // through the stream that it cannot get through the API.
    public boolean canAccess(
            String rule,
            CollectionRules rules,
            AuthContext auth,
            Document record,
            Map<String, Object> body,
            String collection,
            TenantContext ctx) {

        if (isMembershipRule(rule)) {
            return membershipAccess(rule, rules, auth, record, body, ctx);
        }

        String ownerField = rules != null ? rules.ownerFieldOrDefault() : "owner";
        return canAccess(rule, ownerField, auth, record, body, collection);
    }

    private boolean membershipAccess(
            String rule,
            CollectionRules rules,
            AuthContext auth,
            Document record,
            Map<String, Object> body,
            TenantContext ctx) {

        if (auth == null || !auth.isAuthenticated() || rules == null || !rules.hasMembershipLookup()) {
            return false;
        }

        MembershipResolver.Membership membership = membershipResolver.membership(ctx, rules, auth);

        if (isPeersRule(rule)) {
            // Create is never granted; sign-up goes through POST /api/auth/register
            if (record == null) {
                return false;
            }
            Object id = record.get("id");
            return id != null && membership.peers().contains(String.valueOf(id));
        }

        String recordField = rules.groupRecordField();
        if (StringUtils.isBlank(recordField)) {
            return false;
        }

        if (record == null) {
            // CREATE: the body must name only groups of the caller; nothing is filled in here
            return body != null && membership.inEveryGroup(MembershipResolver.valuesOf(body.get(recordField)));
        }

        if (!membership.inAnyGroup(MembershipResolver.valuesOf(record.get(recordField)))) {
            return false;
        }

        // An update must not move a record into a group the caller does not belong to
        if (body != null && body.containsKey(recordField)) {
            return membership.inEveryGroup(MembershipResolver.valuesOf(body.get(recordField)));
        }

        return true;
    }

    public Bson listFilter(String rule, String ownerField, AuthContext auth) {
        return listFilter(rule, ownerField, auth, null);
    }

    public Bson listFilter(String rule, String ownerField, AuthContext auth, String collection) {
        RuleMode mode = resolveMode(rule);
        if (mode == RuleMode.LOCKED) {
            return null;
        }
        if (mode == RuleMode.PUBLIC) {
            return Filters.empty();
        }

        // Membership cannot be resolved here; a null filter is refused by the caller
        if (isMembershipRule(rule)) {
            return null;
        }

        String normalized = normalizeRule(rule, ownerField, collection);
        RuleNode node = RuleParser.parse(normalized);
        return RuleToMongoConverter.toFilter(node, auth);
    }

    // ANDed onto the client's filter, never used in its place
    public Bson listFilter(
            String rule,
            CollectionRules rules,
            AuthContext auth,
            String collection,
            TenantContext ctx) {

        if (isMembershipRule(rule)) {
            return membershipListFilter(rule, rules, auth, ctx);
        }

        String ownerField = rules != null ? rules.ownerFieldOrDefault() : "owner";
        return listFilter(rule, ownerField, auth, collection);
    }

    private Bson membershipListFilter(String rule, CollectionRules rules, AuthContext auth, TenantContext ctx) {
        if (auth == null || !auth.isAuthenticated() || rules == null || !rules.hasMembershipLookup()) {
            return null;
        }

        MembershipResolver.Membership membership = membershipResolver.membership(ctx, rules, auth);

        if (isPeersRule(rule)) {
            return Filters.in("id", membership.peers());
        }

        if (StringUtils.isBlank(rules.groupRecordField())) {
            return null;
        }

        Set<String> groups = membership.groups();
        if (groups.isEmpty()) {
            // Explicit IMPOSSIBLE: Filters.empty() would open the whole collection
            return RuleToMongoConverter.IMPOSSIBLE;
        }

        return Filters.in(rules.groupRecordField(), groups);
    }

    private boolean isOwnerRule(String rule) {
        return rule != null && "owner".equalsIgnoreCase(rule.trim());
    }
}
