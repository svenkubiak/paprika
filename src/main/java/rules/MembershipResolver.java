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

import java.util.*;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;

// The membership collection is read internally, deliberately without applying its own rules.
// Nothing is cached beyond one operation, so a revoked membership takes effect on the next request.
@Singleton
public final class MembershipResolver {
    private final Provider<TenantCollectionService> tenantCollections;

    // Provider breaks the cycle RuleService -> this -> TenantCollectionService -> RuleService
    @Inject
    public MembershipResolver(Provider<TenantCollectionService> tenantCollections) {
        this.tenantCollections = Objects.requireNonNull(tenantCollections, "tenantCollections must not be null");
    }

    private MembershipResolver() {
        this.tenantCollections = null;
    }

    // No database behind it: every membership is empty, so the group presets deny
    public static MembershipResolver denying() {
        return new MembershipResolver();
    }

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

    // A RELATION field holds a single id or a list of them
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

    // Per-operation memo; must never outlive the request that created it
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

        public Set<String> groups() {
            if (groups == null) {
                groups = resolver.loadGroups(ctx, rules, auth);
            }
            return groups;
        }

        // Always includes the caller, who reaches their own record even without any membership
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

        public boolean inAnyGroup(List<String> candidates) {
            Set<String> mine = groups();
            return !mine.isEmpty() && candidates.stream().anyMatch(mine::contains);
        }

        // For client-supplied values: a write must never add a group the caller is not in
        public boolean inEveryGroup(List<String> candidates) {
            Set<String> mine = groups();
            return !candidates.isEmpty() && !mine.isEmpty() && mine.containsAll(candidates);
        }
    }
}
