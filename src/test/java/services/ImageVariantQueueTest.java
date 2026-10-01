package services;

import auth.TenantContext;
import enums.FieldType;
import models.CollectionDefinition;
import models.FieldDefinition;
import models.FieldOptions;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import utils.MultipartSupport;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * A single worker on a bounded queue: at most one decode at a time, and a full queue or a vanished
 * file costs only the variants, never the upload or an orphan in storage.
 */
class ImageVariantQueueTest {
    private static final TenantContext CTX = TenantContext.guest("tenant-1", "database");

    @TempDir
    Path storageRoot;

    @Test
    void theUploadIsDoneBeforeTheVariantsAndTheyFollow() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot);
        ImageVariantQueue queue = new ImageVariantQueue(storage, 10);
        FileFieldService files = new FileFieldService(storage, queue);
        CountDownLatch release = holdWorker(queue);

        String fileId = commitUpload(files);
        assertThat("the original is there at once", storage.read(CTX, fileId), notNullValue());
        assertThat("the variants are not produced in the request", storage.variantWidths(CTX, fileId), is(empty()));

        release.countDown();
        assertThat(queue.awaitIdle(Duration.ofSeconds(10)), is(true));
        assertThat(storage.variantWidths(CTX, fileId), contains(100));
    }

    @Test
    void aFullQueueCostsTheVariantsButNotTheUpload() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot);
        ImageVariantQueue queue = new ImageVariantQueue(storage, 1);
        FileFieldService files = new FileFieldService(storage, queue);
        CountDownLatch release = holdWorker(queue);

        String running = commitUpload(files);
        // The worker has taken the first job off the queue once it waits at the gate
        awaitTaken(queue);
        String queued = commitUpload(files);
        String rejected = commitUpload(files);

        release.countDown();
        assertThat(queue.awaitIdle(Duration.ofSeconds(10)), is(true));

        assertThat(storage.variantWidths(CTX, running), contains(100));
        assertThat(storage.variantWidths(CTX, queued), contains(100));
        assertThat("no room left, so no variants", storage.variantWidths(CTX, rejected), is(empty()));
        assertThat("but the upload itself is intact", storage.read(CTX, rejected), notNullValue());
    }

    @Test
    void aFileDeletedBeforeItsTurnLeavesNoVariantBehind() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot);
        ImageVariantQueue queue = new ImageVariantQueue(storage, 10);
        FileFieldService files = new FileFieldService(storage, queue);
        CountDownLatch release = holdWorker(queue);

        String fileId = commitUpload(files);
        storage.delete(CTX, fileId);

        release.countDown();
        assertThat(queue.awaitIdle(Duration.ofSeconds(10)), is(true));
        assertThat(storage.variantWidths(CTX, fileId), is(empty()));
    }

    private static CountDownLatch holdWorker(ImageVariantQueue queue) {
        CountDownLatch release = new CountDownLatch(1);
        queue.hold = release;
        return release;
    }

    private static void awaitTaken(ImageVariantQueue queue) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (queue.queuedJobs() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    private static String commitUpload(FileFieldService files) throws IOException {
        FileFieldService.UploadChanges changes = files.applyUploads(
                CTX,
                definition(),
                new Document(),
                Map.of("attachment", List.of(new MultipartSupport.UploadedFile("p.png", png(400, 200), "image/png"))),
                false);
        files.commitUploads(CTX, changes);
        return changes.storedFileIds().getFirst();
    }

    private static CollectionDefinition definition() {
        return new CollectionDefinition(
                "definition-id",
                "documents",
                List.of(new FieldDefinition("attachment", FieldType.FILE, false, true,
                        FieldOptions.forFile(5L * 1024 * 1024, List.of(), 1, List.of(100)))),
                List.of(),
                null,
                false);
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
