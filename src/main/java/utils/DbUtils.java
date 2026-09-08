package utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.mangoo.core.Application;
import io.mangoo.persistence.interfaces.Datastore;
import io.mangoo.utils.CommonUtils;
import models.Stats;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

public final class DbUtils {
    public static Object toMongoValue(JsonNode value) {
        if (value.isTextual()) {
            return value.asText();
        }

        if (value.isBoolean()) {
            return value.asBoolean();
        }

        if (value.isInt()) {
            return value.asInt();
        }

        if (value.isLong()) {
            return value.asLong();
        }

        if (value.isFloatingPointNumber()) {
            return value.asDouble();
        }

        if (value.isNull()) {
            return null;
        }

        if (value.isObject()) {
            return Document.parse(value.toString());
        }

        if (value.isArray()) {
            List<Object> items = new ArrayList<>();
            for (JsonNode item : value) {
                items.add(toMongoValue(item));
            }
            return items;
        }

        throw new IllegalArgumentException("Unsupported JSON type: " + value.getNodeType());
    }

    public static String id() {
        return CommonUtils.uuidV7();
    }

    public static Stats getStats() {
        Datastore datastore = Application.getInstance(Datastore.class);
        MongoDatabase mongoDatabase = datastore.getMongoDatabase();
        long collections = 0;
        for (String ignored : mongoDatabase.listCollectionNames()) {
            collections++;
        }

        long records = 0;
        for (String collectionName : mongoDatabase.listCollectionNames()) {
            MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);
            records += collection.estimatedDocumentCount();
        }

        return new Stats(datastore.isHealthy(), true, collections, records, 0, Application.getUptime().toSeconds());
    }
}
