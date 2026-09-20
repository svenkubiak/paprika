package rules;

import auth.AuthContext;
import auth.TenantContext;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import models.CollectionRules;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import services.TenantCollectionService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;

/**
 * Answers the one question the {@code group} and {@code peers} presets need and the rule
 * expression engine cannot express: which groups does the caller belong to, and who else is in
 * them. Both are a lookup in a second collection - the memberships - which
 * {@link RuleParser}/{@link RuleEvaluator}/{@link RuleToMongoConverter} have no notion of, so the
 * two presets are decided in {@link RuleService} before anything is parsed.
 * <p>
 * <b>The rules of the membership collection are deliberately not applied here.</b> This resolver
 * reads it internally, on behalf of Paprika itself, the same way the owner field of a record is
 * read without asking whether the caller may see that field. That says nothing about who may call
 * {@code /api/collections/<groupCollection>} - that collection keeps its own rules, and they
 * should normally be locked or scoped like any other.
 * <p>
 * Nothing is cached beyond a single operation: a membership that is revoked has to take effect on
 * the very next request, not when a time window runs out. {@link Membership} is the memo, it is
 * created per operation and dies with it.
 */
@Singleton
public final class MembershipResolver {
    private final Provider<TenantCollectionService> tenantCollections;

    /**
     * A {@link Provider} instead of the service itself: {@link RuleService} depends on this
     * resolver and {@code TenantCollectionService} depends on {@code RuleService} for its
     * validation, which is a cycle the injector cannot construct eagerly.
     */
    @Inject
    public MembershipResolver(Provider<TenantCollectionService> tenantCollections) {
        this.tenantCollections = Objects.requireNonNull(tenantCollections, "tenantCollections must not be null");
    }

    private MembershipResolver() {
        this.tenantCollections = null;
    }

    /**
     * A resolver without a database behind it, for the unit tests and for any {@link RuleService}
     * built outside the injector. Every membership is empty, so the two presets deny - the safe
     * direction, and never a silently open collection.
     */
    public static MembershipResolver denying() {
        return new MembershipResolver();
    }

    /**
     * The caller's membership for one operation. Every lookup is made at most once per instance,
     * so an operation that asks twice - an update checking the old and the new group of a record -
     * costs one query, and an instance never survives the request that created it.
     */
    public Membership membership(TenantContext ctx, CollectionRules rules, AuthContext auth) {
        return new Membership(this, ctx, rules, auth);
    }

    private Set<String> loadGroups(TenantContext ctx, CollectionRules rules, AuthContext auth) {
        if (tenantCollections == null || ctx == null || rules == null || auth == null
                || !auth.isAuthenticated() || !rules.hasMembershipLookup()) {
            return Set.of();
        }

        List<Document> memberships = tenantCollections.get()
                .dataCollection(ctx, rules.groupCollection())
                .find(eq(rules.groupMemberField(), auth.id()))
                .into(new ArrayList<>());

        return values(memberships, rules.groupField());
    }

    private Set<String> loadMembers(TenantContext ctx, CollectionRules rules, Set<String> groups) {
        if (tenantCollections == null || ctx == null || rules == null || groups.isEmpty()) {
            return Set.of();
        }

        List<Document> memberships = tenantCollections.get()
                .dataCollection(ctx, rules.groupCollection())
                .find(in(rules.groupField(), groups))
                .into(new ArrayList<>());

        return values(memberships, rules.groupMemberField());
    }

    /**
     * A RELATION field can hold a single id or a list of them, so both shapes are collected.
     */
    private static Set<String> values(List<Document> documents, String field) {
        Set<String> collected = new HashSet<>();
        for (Document document : documents) {
            collected.addAll(valuesOf(document.get(field)));
        }
        return Set.copyOf(collected);
    }

    public static List<String> valuesOf(Object value) {
        if (value == null) {
            return List.of();
        }

        List<String> collected = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object element : collection) {
                if (element != null && StringUtils.isNotBlank(String.valueOf(element))) {
                    collected.add(String.valueOf(element));
                }
            }
        } else if (StringUtils.isNotBlank(String.valueOf(value))) {
            collected.add(String.valueOf(value));
        }

        return List.copyOf(collected);
    }

    /**
     * The memo of one operation: the groups of the caller and, for {@code peers}, the users that
     * share one of them. Both are resolved on first use and kept for the lifetime of this object
     * only - which is the request that created it.
     */
    public static final class Membership {
        private final MembershipResolver resolver;
        private final TenantContext ctx;
        private final CollectionRules rules;
        private final AuthContext auth;

        private Set<String> groups;
        private Set<String> peers;

        private Membership(
                MembershipResolver resolver,
                TenantContext ctx,
                CollectionRules rules,
                AuthContext auth) {
            this.resolver = resolver;
            this.ctx = ctx;
            this.rules = rules;
            this.auth = auth;
        }

        /** The groups the caller is a member of. Empty for a guest, and empty means nothing. */
        public Set<String> groups() {
            if (groups == null) {
                groups = resolver.loadGroups(ctx, rules, auth);
            }
            return groups;
        }

        /**
         * Everyone sharing at least one group with the caller, plus the caller. The own id is
         * always in there: a user reaches their own record even without any membership.
         */
        public Set<String> peers() {
            if (peers == null) {
                if (auth == null || !auth.isAuthenticated()) {
                    peers = Set.of();
                } else {
                    Set<String> resolved = new HashSet<>(resolver.loadMembers(ctx, rules, groups()));
                    resolved.add(auth.id());
                    peers = Set.copyOf(resolved);
                }
            }
            return peers;
        }

        /** Whether at least one of the given values names a group the caller belongs to. */
        public boolean inAnyGroup(List<String> candidates) {
            Set<String> mine = groups();
            return !mine.isEmpty() && candidates.stream().anyMatch(mine::contains);
        }

        /**
         * Whether every given value names a group the caller belongs to - the check for a value a
         * client supplied. A write must never put a record into a group the caller is not in, not
         * even alongside one it is in.
         */
        public boolean inEveryGroup(List<String> candidates) {
            Set<String> mine = groups();
            return !candidates.isEmpty() && !mine.isEmpty() && mine.containsAll(candidates);
        }
    }
}
