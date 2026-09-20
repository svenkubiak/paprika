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

    /**
     * For unit tests and any caller that evaluates the presets which need nothing but the rule
     * itself. The membership presets deny in such an instance, because the lookup they depend on
     * has no database to run against.
     */
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

    /**
     * Resolution for a collection whose records point at their owner - every collection but
     * {@code users}.
     * <p>
     * The collection travels as an optional trailing argument rather than through a new parameter
     * on every signature: only the {@code owner} preset cares about it, the callers that know the
     * collection are few (the data-plane auth filter and the realtime delivery check), and this
     * way every other caller - including the rule unit tests - keeps working unchanged.
     */
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
            // See OwnerFieldUtils#isSelfOwnedCollection: on the users collection the own record is
            // the caller's own account, not a relation pointing at it
            return OwnerFieldUtils.isSelfOwnedCollection(collection)
                    ? "record.id = auth.id"
                    : "record." + ownerField + " = auth.id";
        }
        return trimmed;
    }

    /**
     * Whether a rule is decided by a membership lookup instead of by the expression engine. These
     * two presets never reach {@link RuleParser}: a subquery into a second collection is not
     * something the rule language can express, so {@link #canAccess} and {@link #listFilter}
     * answer them before any parsing happens.
     */
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

    /**
     * Validates the rule values and, for the membership presets, the configuration they cannot
     * work without. An incompletely configured {@code group} or {@code peers} rule is rejected
     * here rather than silently treated as locked at request time: a collection whose rules do not
     * mean what they say is worse than a save that fails.
     * <p>
     * Whether the configured collection and fields actually exist is checked by
     * {@code TenantCollectionService#validateDefinition(TenantContext, CollectionDefinition)},
     * which is the layer that can look at the other collections of the tenant.
     */
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
            // "peers" compares the record id against the members; only a collection whose records
            // are the users themselves has an id to compare. "group" is the opposite case.
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

        // groupRecordField "id" means the record is the group itself. A "group" create rule could
        // then never be satisfied: "id" is read-only on write, so the body cannot name the group -
        // and nobody is a member of a group that does not exist yet. A rule that always denies
        // without saying so is worse than a refused save; who may create a group is decided by a
        // different preset ("auth" or "owner").
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

        // Reached without a tenant context and without the collection's rules, so the membership
        // lookup cannot run. Deny rather than fall through to the parser, which would not know
        // these values either.
        if (isMembershipRule(rule)) {
            return false;
        }

        // A create on a self-owned collection has no record yet whose id could equal the caller's,
        // so "own records" can never be satisfied there. Deciding it here rather than letting the
        // expression decide keeps a client-supplied id out of the question: sending one's own id in
        // the body must not turn into permission to insert a second record under that identity.
        // Sign-up goes through POST /api/auth/register.
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

    /**
     * The full decision, including the presets that need a lookup in a second collection. Callers
     * that can supply the tenant context and the collection's rules - the data-plane auth filter
     * and the realtime delivery check - use this one, so that both answer a membership rule the
     * same way. Anything a client cannot get through the API it must not get through the stream.
     */
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
            // There is no record yet whose identity could be checked, so create is never granted -
            // sign-up goes through POST /api/auth/register, exactly as with "owner".
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
            // CREATE: the client says which group the record joins, and it has to be one of the
            // caller's. A body without the field is rejected - nothing is filled in here.
            return body != null && membership.inEveryGroup(MembershipResolver.valuesOf(body.get(recordField)));
        }

        if (!membership.inAnyGroup(MembershipResolver.valuesOf(record.get(recordField)))) {
            return false;
        }

        // An update may not move a record out of the caller's reach - or into a group the caller
        // does not belong to. Only a body that actually carries the field is checked.
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

        // Without tenant context and rules the membership cannot be resolved; a null filter is
        // refused by the caller, which is the only safe answer here.
        if (isMembershipRule(rule)) {
            return null;
        }

        String normalized = normalizeRule(rule, ownerField, collection);
        RuleNode node = RuleParser.parse(normalized);
        return RuleToMongoConverter.toFilter(node, auth);
    }

    /**
     * The scoping query for a list, including the membership presets. The result is anded onto the
     * client's filter by {@code CollectionRecordService#list}, never used in its place.
     */
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
            // Always contains the caller's own id, so a user without any membership still sees
            // their own record - and nothing else.
            return Filters.in("id", membership.peers());
        }

        if (StringUtils.isBlank(rules.groupRecordField())) {
            return null;
        }

        Set<String> groups = membership.groups();
        if (groups.isEmpty()) {
            // No membership means no records. An empty Filters.in() would match nothing as well,
            // but saying it explicitly keeps the one mistake that opens the whole collection -
            // answering with Filters.empty() - from ever looking like an option here.
            return RuleToMongoConverter.IMPOSSIBLE;
        }

        return Filters.in(rules.groupRecordField(), groups);
    }

    private boolean isOwnerRule(String rule) {
        return rule != null && "owner".equalsIgnoreCase(rule.trim());
    }
}
