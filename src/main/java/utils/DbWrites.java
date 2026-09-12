package utils;

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
        return e instanceof MongoWriteException write && write.getError().getCode() == DUPLICATE_KEY_CODE;
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
