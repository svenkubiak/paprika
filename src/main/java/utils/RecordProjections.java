package utils;

import com.mongodb.client.model.Projections;
import org.bson.conversions.Bson;

// Every data-plane read must use this projection; a hand-written copy risks leaking user credentials.
public final class RecordProjections {
    private RecordProjections() {
    }

    public static Bson forCollection(String collection) {
        return UserRecordUtils.isUsers(collection)
                ? UserRecordUtils.recordProjection()
                : Projections.excludeId();
    }
}