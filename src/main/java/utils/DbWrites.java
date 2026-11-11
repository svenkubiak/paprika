package utils;

import com.mongodb.DuplicateKeyException;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoDatabase;

// The unique index, not the pre-check, prevents duplicates; its error is translated into the same
// answer the pre-check gives instead of surfacing as a server error.
public final class DbWrites {
    private static final int DUPLICATE_KEY_CODE = 11000;
    private static final int NAMESPACE_EXISTS_CODE = 48;

    private DbWrites() {
    }

    /**
     * Instances starting at the same time all find the collection missing; whoever creates it
     * second gets NamespaceExists, which only confirms that the collection is there.
     */
    public static void createCollectionIfMissing(MongoDatabase database, String name) {
        for (String existing : database.listCollectionNames()) {
            if (name.equals(existing)) {
                return;
            }
        }

        createCollection(database, name);
    }

    // The step after the lookup, where another instance may already have been faster
    static void createCollection(MongoDatabase database, String name) {
        try {
            database.createCollection(name);
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != NAMESPACE_EXISTS_CODE) {
                throw e;
            }
        }
    }

    public static boolean isDuplicateKey(RuntimeException e) {
        if (e instanceof MongoWriteException write) {
            return write.getError().getCode() == DUPLICATE_KEY_CODE;
        }

        // Building a unique index over non-unique data fails as a command, not as a write
        if (e instanceof MongoCommandException command) {
            return command.getErrorCode() == DUPLICATE_KEY_CODE;
        }

        return e instanceof DuplicateKeyException;
    }

    /** For startup writes: a duplicate means that another instance has written the same first. */
    public static void ignoreDuplicate(Runnable write) {
        try {
            write.run();
        } catch (RuntimeException e) {
            if (!isDuplicateKey(e)) {
                throw e;
            }
        }
    }

    public static void rejectDuplicateAs(String message, Runnable write) {
        try {
            write.run();
        } catch (RuntimeException e) {
            if (isDuplicateKey(e)) {
                throw new IllegalArgumentException(message, e);
            }
            throw e;
        }
    }
}
