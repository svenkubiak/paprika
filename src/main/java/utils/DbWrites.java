package utils;

import com.mongodb.DuplicateKeyException;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;

/**
 * Helpers for writes that race against a unique index.
 * <p>
 * Checking whether a name is free and inserting afterwards are two operations: two requests can both
 * find the name free and both insert. The unique index is what actually prevents the duplicate, so
 * the check before it is a convenience for the error message, not the guarantee - and the write
 * error it produces has to be translated into the same answer the check would have given instead of
 * surfacing as a server error.
 */
public final class DbWrites {
    private static final int DUPLICATE_KEY_CODE = 11000;

    private DbWrites() {
    }

    public static boolean isDuplicateKey(RuntimeException e) {
        if (e instanceof MongoWriteException write) {
            return write.getError().getCode() == DUPLICATE_KEY_CODE;
        }

        // Building a unique index over data that is not unique fails as a command, not as a write,
        // so the write path alone does not catch it.
        if (e instanceof MongoCommandException command) {
            return command.getErrorCode() == DUPLICATE_KEY_CODE;
        }

        return e instanceof DuplicateKeyException;
    }

    /**
     * Runs a write and turns a duplicate key error into an {@link IllegalArgumentException} with the
     * given message, so callers handle a lost race exactly like a detected conflict.
     */
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
