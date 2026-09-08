package utils;

import auth.TenantContext;
import enums.FieldType;
import io.mangoo.core.Application;
import models.CollectionDefinition;
import models.CollectionRules;
import models.FieldDefinition;
import models.TenantDefinition;
import org.bson.Document;
import services.TenantCollectionService;
import services.TenantService;

import java.util.List;

public final class TenantTestUtils {
    private TenantTestUtils() {
    }

    public static TenantDefinition defaultTenant() {
        TenantService tenantService = Application.getInstance(TenantService.class);
        return tenantService.findBySlug("default")
                .orElseThrow(() -> new IllegalStateException("Default tenant not initialized"));
    }

    public static TenantContext defaultTenantContext() {
        TenantDefinition tenant = defaultTenant();
        return TenantContext.guest(tenant.id(), tenant.databaseName());
    }

    public static void seedCollection(String name, CollectionRules rules) {
        seedCollection(name, rules, List.of(new FieldDefinition("title", FieldType.STRING, true, false, null)));
    }

    public static void seedCollection(String name, CollectionRules rules, List<FieldDefinition> fields) {
        TenantContext ctx = defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);

        if (collections.findDefinition(ctx, name) != null) {
            return;
        }

        collections.insertDefinition(ctx, new CollectionDefinition(
                DbUtils.id(),
                name,
                fields,
                List.of(),
                rules,
                false
        ));
    }

    public static String seedRecord(String collection, String title) {
        TenantContext ctx = defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        String id = DbUtils.id();
        collections.dataCollection(ctx, collection).insertOne(new Document()
                .append("id", id)
                .append("title", title));
        return id;
    }

    public static String loginBody(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    public static String loginBody(String tenant, String username, String password) {
        return "{\"tenant\":\"" + tenant + "\",\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }
}
