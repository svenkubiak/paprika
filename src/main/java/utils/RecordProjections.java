package utils;

import com.mongodb.client.model.Projections;
import org.bson.conversions.Bson;

/**
 * The projection every data-plane read of a record goes through. It lives here rather than inside
 * a single service so that every path that hands a record back - the record service and the
 * cascading delete alike - hides the same fields. A second, hand-written copy of this is how the
 * users collection starts leaking credentials.
 */
public final class RecordProjections {
    private RecordProjections() {
    }

    public static Bson forCollection(String collection) {
        return UserRecordUtils.isUsers(collection)
                ? UserRecordUtils.recordProjection()
                : Projections.excludeId();
    }
}