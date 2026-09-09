package services;

import auth.TenantContext;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import constants.CollectionName;
import exceptions.NoTenantContextException;
import io.mangoo.persistence.interfaces.Datastore;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bson.Document;

import java.util.Objects;

@Singleton
public class TenantDatabaseResolver {
    private final MongoClient mongoClient;
    private final Datastore systemDatastore;

    @Inject
    public TenantDatabaseResolver(Datastore systemDatastore) {
        this.systemDatastore = Objects.requireNonNull(systemDatastore, "systemDatastore must not be null");
        this.mongoClient = systemDatastore.getMongoClient();
    }

    public MongoDatabase system() {
        return systemDatastore.getMongoDatabase();
    }

    public MongoDatabase tenantDatabase(String databaseName) {
        return mongoClient.getDatabase(databaseName);
    }

    public MongoDatabase tenant(TenantContext ctx) {
        if (!ctx.hasTenantContext()) {
            throw new NoTenantContextException();
        }
        return mongoClient.getDatabase(ctx.databaseName());
    }

    public MongoCollection<Document> tenantCollection(TenantContext ctx, String name) {
        return tenant(ctx).getCollection(name);
    }

    public MongoCollection<Document> tenantDataCollection(TenantContext ctx, String logicalName) {
        return tenant(ctx).getCollection(CollectionName.physicalTenantData(logicalName));
    }

    public MongoCollection<Document> tenantMetaCollection(TenantContext ctx, String logicalName) {
        return tenant(ctx).getCollection(CollectionName.meta(logicalName));
    }

    public MongoCollection<Document> systemCollection(String name) {
        return system().getCollection(name);
    }
}
