package utils;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantDatabaseResolver;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Instances starting at the same time run the same startup writes; the one that comes second must
 * find them done instead of failing its start.
 */
@ExtendWith({TestRunner.class})
class DbWritesTest {

    @Test
    void creatingACollectionThatAnotherInstanceCreatedFirstSucceeds() {
        MongoDatabase database = system();
        String name = "dbwrites_" + DbUtils.id();

        try {
            DbWrites.createCollection(database, name);
            DbWrites.createCollection(database, name);

            assertThat(database.listCollectionNames().into(new java.util.ArrayList<>()), hasItem(name));
        } finally {
            database.getCollection(name).drop();
        }
    }

    @Test
    void aDuplicateFromAnotherInstanceIsIgnored() {
        var collection = system().getCollection("dbwrites_" + DbUtils.id());
        collection.createIndex(Indexes.ascending("key"), new IndexOptions().unique(true));

        try {
            DbWrites.ignoreDuplicate(() -> collection.insertOne(new Document("key", "default")));
            DbWrites.ignoreDuplicate(() -> collection.insertOne(new Document("key", "default")));

            assertThat(collection.countDocuments(), equalTo(1L));
        } finally {
            collection.drop();
        }
    }

    @Test
    void otherFailuresAreNotIgnored() {
        assertThrows(IllegalStateException.class,
                () -> DbWrites.ignoreDuplicate(() -> {
                    throw new IllegalStateException("not a duplicate");
                }));
    }

    private static MongoDatabase system() {
        return Application.getInstance(TenantDatabaseResolver.class).system();
    }
}
