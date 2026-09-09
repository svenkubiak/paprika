package services;

import com.mongodb.client.model.Updates;
import constants.CollectionName;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bson.Document;
import utils.DbUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static com.mongodb.client.model.Filters.eq;

@Singleton
public class SettingsService {
    private final TenantDatabaseResolver resolver;

    @Inject
    public SettingsService(TenantDatabaseResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    }

    public String get(String key, String defaultValue) {
        Document document = resolver.systemCollection(CollectionName.SETTINGS).find(eq("key", key)).first();
        if (document == null) {
            return defaultValue;
        }
        return document.getString("value");
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key, null);
        if (value == null) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value.trim());
    }

    public int getInt(String key, int defaultValue) {
        String value = get(key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public void set(String key, String value) {
        String now = Instant.now().toString();
        var collection = resolver.systemCollection(CollectionName.SETTINGS);
        Document existing = collection.find(eq("key", key)).first();

        if (existing == null) {
            collection.insertOne(new Document()
                    .append("id", DbUtils.id())
                    .append("key", key)
                    .append("value", value)
                    .append("updatedAt", now));
            return;
        }

        collection.updateOne(
                eq("key", key),
                Updates.combine(Updates.set("value", value), Updates.set("updatedAt", now))
        );
    }

    public Map<String, String> getAll() {
        Map<String, String> settings = new LinkedHashMap<>();
        for (Document document : resolver.systemCollection(CollectionName.SETTINGS).find()) {
            settings.put(document.getString("key"), document.getString("value"));
        }
        return settings;
    }
}
