package utils;

import com.mongodb.DuplicateKeyException;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;

// The unique index, not the pre-check, prevents duplicates; its error is translated into the same
// answer the pre-check gives instead of surfacing as a server error.
public final class DbWrites {
    private static final int DUPLICATE_KEY_CODE = 11000;

    private DbWrites() {
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

    public static void rejectDuplicateAs(String message, Runnable write) {
        try {
            write.run();
        } catch (RuntimeException e) {
            if (isDuplicateKey(e)) {
                throw new IllegalArgumentException(message);
            }
            throw e;
        }
    }
}
