package services;

import constants.CollectionName;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.DbUtils;

import static com.mongodb.client.model.Filters.eq;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

@ExtendWith({TestRunner.class})
class SettingsServiceTest {

    @Test
    void writingAKeyAgainUpdatesItsOneDocument() {
        SettingsService settings = Application.getInstance(SettingsService.class);
        var collection = Application.getInstance(TenantDatabaseResolver.class).systemCollection(CollectionName.SETTINGS);
        String key = "test.upsert." + DbUtils.id();

        try {
            settings.set(key, "first");
            String id = collection.find(eq("key", key)).first().getString("id");
            settings.set(key, "second");

            assertThat(settings.get(key, null), equalTo("second"));
            assertThat(collection.countDocuments(eq("key", key)), equalTo(1L));
            assertThat("the id is written once, on insert",
                    collection.find(eq("key", key)).first().getString("id"), equalTo(id));
        } finally {
            collection.deleteOne(eq("key", key));
        }
    }
}
