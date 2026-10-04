package services;

import auth.TenantContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import utils.ImageVariants;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A single worker bounds decodes instance-wide: decode memory scales with declared pixels, so
 * concurrent decodes in request threads could exhaust the heap. A full queue or a restart only
 * loses variants; downloads fall back to a wider variant or the original.
 */
@Singleton
public class ImageVariantQueue {
    private static final Logger LOG = LogManager.getLogger(ImageVariantQueue.class);
    private static final int CAPACITY = 100;

    public record Job(String fileId, String mimeType, List<Integer> widths, String fieldName) {
        public Job {
            widths = List.copyOf(widths);
        }
    }

    private final FileStorageService storage;
    private final ThreadPoolExecutor worker;
    private final AtomicInteger pending = new AtomicInteger();

    // Lets a test hold the worker to fill the queue deterministically
    volatile CountDownLatch hold;

    @Inject
    public ImageVariantQueue(FileStorageService storage) {
        this(storage, CAPACITY);
    }

    ImageVariantQueue(FileStorageService storage, int capacity) {
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.worker = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                Thread.ofPlatform().name("paprika-image-variants").daemon().factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** Call only once the record is written. {@code false} when the queue is full. */
    public boolean submit(TenantContext ctx, Job job) {
        pending.incrementAndGet();
        try {
            worker.execute(() -> run(ctx, job));
            return true;
        } catch (RejectedExecutionException e) {
            pending.decrementAndGet();
            LOG.warn("Image variant queue is full, field {} keeps its original without variants", job.fieldName());
            return false;
        }
    }

    // For tests, which otherwise race the worker
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (pending.get() > 0) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    int queuedJobs() {
        return worker.getQueue().size();
    }

    private void run(TenantContext ctx, Job job) {
        try {
            CountDownLatch gate = hold;
            if (gate != null) {
                gate.await();
            }
            render(ctx, job);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pending.decrementAndGet();
        }
    }

    private void render(TenantContext ctx, Job job) {
        try {
            byte[] original = storage.read(ctx, job.fileId());
            if (original == null) {
                return;
            }

            Map<Integer, byte[]> variants = ImageVariants.scaleToWidths(original, job.mimeType(), job.widths());
            for (Map.Entry<Integer, byte[]> variant : variants.entrySet()) {
                // The file may have been dropped during the decode; its variants would be unreachable
                if (!storage.exists(ctx, job.fileId())) {
                    return;
                }
                storage.store(ctx, FileStorageService.variantKey(job.fileId(), variant.getKey()), variant.getValue());
            }

            // It may also have been dropped between the check and the write
            if (!storage.exists(ctx, job.fileId())) {
                storage.delete(ctx, job.fileId());
            }
        } catch (IOException | RuntimeException e) {
            // Logs only the field name, never file content
            LOG.warn("Failed to create the image variants for field {}: {}", job.fieldName(), e.getMessage());
        }
    }
}
