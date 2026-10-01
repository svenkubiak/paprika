package services;

import auth.AuthContext;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Projections;
import constants.CollectionName;
import constants.SystemCollections;
import enums.Role;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.TenantDefinition;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;

import java.util.Objects;
import java.util.Optional;

import static com.mongodb.client.model.Filters.eq;

/**
 * A token is valid only while its version matches the account's {@link #FIELD} counter, so raising
 * the counter revokes all of the account's stateless tokens. A missing field reads as {@code 0}.
 */
@Singleton
public class TokenVersionService {
    public static final String FIELD = "tokenVersion";

    private final TenantDatabaseResolver resolver;
    private final TenantService tenantService;
    private final RealtimeService realtimeService;

    @Inject
    public TokenVersionService(
            TenantDatabaseResolver resolver,
            TenantService tenantService,
            RealtimeService realtimeService) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.realtimeService = Objects.requireNonNull(realtimeService, "realtimeService must not be null");
    }

    /** Empty when the account does not exist, so no token of it is valid. */
    public Optional<Integer> current(AuthContext auth) {
        return accounts(auth)
                .map(accounts -> accounts.find(eq("id", auth.id()))
                        .projection(Projections.include(FIELD))
                        .first())
                .map(TokenVersionService::of);
    }

    // Also closes realtime streams: they check the token only at subscribe
    public void revokeAll(AuthContext auth) {
        accounts(auth).ifPresent(accounts ->
                accounts.updateOne(eq("id", auth.id()), new Document("$inc", new Document(FIELD, 1))));

        if (auth != null && Role.SUPERADMIN.equals(auth.role())) {
            realtimeService.revokeUserEverywhere(auth.id());
        } else if (auth != null) {
            realtimeService.revokeUser(auth.tenantId(), auth.id());
        }
    }

    public void revokeAll(TenantDefinition tenant, String userId) {
        revokeAll(AuthContext.of(userId, Role.USER, tenant.id()));
    }

    public void revokeAllOfSuperadmin(String userId) {
        revokeAll(AuthContext.of(userId, Role.SUPERADMIN, null));
    }

    private static int of(Document account) {
        return account.get(FIELD) instanceof Number version ? version.intValue() : 0;
    }

    private Optional<MongoCollection<Document>> accounts(AuthContext auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return Optional.empty();
        }

        if (Role.SUPERADMIN.equals(auth.role())) {
            return Optional.of(resolver.systemCollection(CollectionName.USERS));
        }

        if (StringUtils.isBlank(auth.tenantId())) {
            return Optional.empty();
        }

        return tenantService.findById(auth.tenantId())
                .map(TenantDefinition::databaseName)
                .map(databaseName -> resolver.tenantDatabase(databaseName)
                        .getCollection(CollectionName.tenantData(SystemCollections.USERS)));
    }
}
