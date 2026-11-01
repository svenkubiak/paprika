package utils;

import io.mangoo.utils.CommonUtils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Writes a file under a temporary name next to it and renames it into place. On a storage volume
 * shared by several instances, a reader then gets the old file or the new one, never half of one.
 */
public final class AtomicFiles {
    private static final String TEMPORARY_PREFIX = ".";
    private static final String TEMPORARY_SUFFIX = ".tmp";

    private AtomicFiles() {
    }

    public static void write(Path target, byte[] data) throws IOException {
        Path temporary = target.resolveSibling(
                TEMPORARY_PREFIX + target.getFileName() + "." + CommonUtils.uuidV4() + TEMPORARY_SUFFIX);
        try {
            Files.write(temporary, data, CREATE_NEW, WRITE);
            moveIntoPlace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** A write still in progress, possibly on another instance: neither a stored file nor garbage yet. */
    public static boolean isTemporary(String fileName) {
        return fileName.startsWith(TEMPORARY_PREFIX) && fileName.endsWith(TEMPORARY_SUFFIX);
    }

    private static void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Same directory, so this only happens on file systems without an atomic rename at all
            Files.move(temporary, target, REPLACE_EXISTING);
        }
    }
}
