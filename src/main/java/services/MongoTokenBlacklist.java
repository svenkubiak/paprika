package services;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.mangoo.core.Config;
import io.mangoo.interfaces.TokenBlacklist;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;

/**
 * Keeps mangoo's revocations of admin UI cookies in MongoDB instead of the instance's memory, so a
 * restart does not revive a revoked cookie. A TTL index drops each entry once every cookie it
 * covers has expired anyway.
 */
@Singleton
public class MongoTokenBlacklist implements TokenBlacklist {
    public static final String COLLECTION = "token_revocations";
    private static final String TTL_INDEX = "expiresAt_ttl";
    private static final String TOKEN_PREFIX = "jti:";
    private static final String SUBJECT_PREFIX = "sub:";
    private static final String SINCE = "since";
    private static final String EXPIRES_AT = "expiresAt";

    private final TenantDatabaseResolver resolver;
    private final Duration maxTokenLifetime;

    @Inject
    public MongoTokenBlacklist(TenantDatabaseResolver resolver, Config config) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        Objects.requireNonNull(config, "config must not be null");
        this.maxTokenLifetime = Duration.ofSeconds(Math.max(
                config.getAuthenticationCookieRememberExpires(),
                config.getAuthenticationCookieTokenExpires()));
    }

    public void ensureCollection() {
        var database = resolver.system();
        boolean exists = false;
        for (String name : database.listCollectionNames()) {
            if (COLLECTION.equals(name)) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            database.createCollection(COLLECTION);
        }

        for (Document index : revocations().listIndexes()) {
            if (TTL_INDEX.equals(index.getString("name"))) {
                return;
            }
        }
        revocations().createIndex(Indexes.ascending(EXPIRES_AT),
                new IndexOptions().name(TTL_INDEX).expireAfter(0L, TimeUnit.SECONDS));
    }

    @Override
    public void revoke(String jwtId, Instant expiresAt) {
        requireNonBlank(jwtId, "jwtId");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");

        if (expiresAt.isAfter(Instant.now())) {
            String key = TOKEN_PREFIX + jwtId;
            revocations().replaceOne(eq("_id", key),
                    new Document("_id", key).append(EXPIRES_AT, Date.from(expiresAt)),
                    new ReplaceOptions().upsert(true));
        }
    }

    @Override
    public void revokeSubject(String subject, Instant since) {
        requireNonBlank(subject, "subject");
        Objects.requireNonNull(since, "since must not be null");

        // $max: an earlier revocation arriving late must not shorten a later one
        revocations().updateOne(eq("_id", SUBJECT_PREFIX + subject),
                Updates.combine(
                        Updates.max(SINCE, Date.from(since)),
                        Updates.max(EXPIRES_AT, Date.from(since.plus(maxTokenLifetime)))),
                new UpdateOptions().upsert(true));
    }

    @Override
    public boolean isRevoked(String jwtId, String subject, Instant issuedAt) {
        List<String> keys = new ArrayList<>(2);
        if (StringUtils.isNotBlank(jwtId)) {
            keys.add(TOKEN_PREFIX + jwtId);
        }
        if (StringUtils.isNotBlank(subject) && issuedAt != null) {
            keys.add(SUBJECT_PREFIX + subject);
        }
        if (keys.isEmpty()) {
            return false;
        }

        for (Document entry : revocations().find(in("_id", keys))) {
            if (entry.getString("_id").startsWith(TOKEN_PREFIX)) {
                return true;
            }

            // iat has second precision, so the cookie reissued to the session that revoked stays valid
            Date since = entry.getDate(SINCE);
            if (since != null && issuedAt.isBefore(since.toInstant().truncatedTo(ChronoUnit.SECONDS))) {
                return true;
            }
        }

        return false;
    }

    private MongoCollection<Document> revocations() {
        return resolver.systemCollection(COLLECTION);
    }

    private static void requireNonBlank(String value, String name) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
