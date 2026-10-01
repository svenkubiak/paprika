package security;

import auth.TenantContext;
import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.core.Config;
import io.mangoo.test.TestRunner;
import models.CollectionDefinition;
import models.FieldDefinition;
import models.FieldOptions;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.FileFieldService;
import services.ImageVariantQueue;
import services.FileStorageService;
import utils.ImageVariants;
import utils.MultipartSupport;
import utils.TenantTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * maxSize bounds compressed bytes, which a decompression bomb sidesteps: ImageIO allocates per declared
 * pixel and the resulting OutOfMemoryError escapes catch (IOException | RuntimeException). The budget
 * is checked against the header, before decode and before storage.
 */
@ExtendWith({TestRunner.class})
class ImageDecodeBudgetIntegrationTest {

    /** Just past the budget, so the test fails if the limit is quietly raised. */
    private static final int WIDE = 6_000;
    private static final int TALL = 5_001;

    @Test
    void readsTheDeclaredSizeFromTheHeader() throws IOException {
        byte[] png = onePixelPerBitPng(WIDE, TALL);

        assertThat("the header has to give up the size without a decode",
                ImageVariants.declaredPixels(png, "image/png"), equalTo((long) WIDE * TALL));
        assertThat((long) WIDE * TALL, greaterThan(ImageVariants.MAX_PIXELS));
        assertThat(ImageVariants.withinPixelBudget(png, "image/png"), is(false));
    }

    @Test
    void refusesToDecodeBeyondTheBudget() throws IOException {
        byte[] png = onePixelPerBitPng(WIDE, TALL);

        // The guard sits on the allocating line, so a future caller that forgets to validate cannot bypass it
        IOException thrown = assertThrows(IOException.class,
                () -> ImageVariants.scaleToWidth(png, "image/png", 128));
        assertThat(thrown.getMessage(), containsString("Refusing to decode"));
    }

    @Test
    void refusesTheUploadBeforeAnythingIsStored() throws IOException {
        FileFieldService files = Application.getInstance(FileFieldService.class);
        TenantContext ctx = TenantTestUtils.defaultTenantContext();

        CollectionDefinition definition = definitionWithVariants();
        byte[] small = onePixelPerBitPng(64, 64);
        byte[] oversized = onePixelPerBitPng(WIDE, TALL);

        Document record = new Document();
        // Good file first on purpose: validation covers the whole field before storing, so neither may land on disk.
        Map<String, List<MultipartSupport.UploadedFile>> uploads = Map.of("attachment", List.of(
                new MultipartSupport.UploadedFile("fine.png", small, "image/png"),
                new MultipartSupport.UploadedFile("huge.png", oversized, "image/png")));

        long before = storedFileCount();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> files.applyUploads(ctx, definition, record, uploads, false));
        assertThat(thrown.getMessage(), containsString("too large"));

        assertThat("a refused upload must not leave files behind", storedFileCount(), equalTo(before));
        assertThat("and must not write the field either", record.get("attachment"), nullValue());
    }

    @Test
    void ordinaryImagesStillGetTheirVariants() throws IOException, InterruptedException {
        FileFieldService files = Application.getInstance(FileFieldService.class);
        FileStorageService storage = Application.getInstance(FileStorageService.class);
        TenantContext ctx = TenantTestUtils.defaultTenantContext();

        Document record = new Document();
        FileFieldService.UploadChanges changes = files.applyUploads(
                ctx,
                definitionWithVariants(),
                record,
                Map.of("attachment", List.of(
                        new MultipartSupport.UploadedFile("ok.png", onePixelPerBitPng(512, 512), "image/png"))),
                false);
        files.commitUploads(ctx, changes);
        assertThat(Application.getInstance(ImageVariantQueue.class).awaitIdle(java.time.Duration.ofSeconds(10)), is(true));

        assertThat("the budget must not break ordinary uploads", record.get("attachment"), notNullValue());

        String fileId = storedFileId(record);
        assertThat("a 512 px image still gets its 128 px variant",
                storage.variantWidths(ctx, fileId), hasItem(128));
    }

    @Test
    void aFieldWithoutVariantsIsNotAffected() throws IOException {
        FileFieldService files = Application.getInstance(FileFieldService.class);
        TenantContext ctx = TenantTestUtils.defaultTenantContext();

        // Nothing decodes this upload, so its dimensions must not be limited.
        Document record = new Document();
        files.applyUploads(
                ctx,
                definition(FieldOptions.forFile(4_000_000L, List.of(), 2)),
                record,
                Map.of("attachment", List.of(
                        new MultipartSupport.UploadedFile("huge.png", onePixelPerBitPng(WIDE, TALL), "image/png"))),
                false);

        assertThat(record.get("attachment"), notNullValue());
    }

    @Test
    void aHardFailureWhileStoringLeavesNothingBehind() throws IOException {
        // A multi-file upload can still fail hard halfway (e.g. memory pressure), leaving an orphaned
        // original in storage. The cleanup has to cover Error too.
        HeapExhaustedOnVariant storage = new HeapExhaustedOnVariant(Application.getInstance(Config.class));
        FileFieldService files = new FileFieldService(storage, new ImageVariantQueue(storage));
        TenantContext ctx = TenantTestUtils.defaultTenantContext();

        long before = storedFileCount();

        Document record = new Document();
        assertThrows(OutOfMemoryError.class, () -> files.applyUploads(
                ctx,
                definitionWithVariants(),
                record,
                Map.of("attachment", List.of(
                        new MultipartSupport.UploadedFile("ok.png", onePixelPerBitPng(512, 512), "image/png"),
                        new MultipartSupport.UploadedFile("fails.png", onePixelPerBitPng(512, 512), "image/png"))),
                false));

        assertThat("the original must not survive a failure that leaves no record behind",
                storedFileCount(), equalTo(before));
    }

    /** One bit per pixel keeps test memory small while the header honestly declares every pixel. */
    private static byte[] onePixelPerBitPng(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static CollectionDefinition definitionWithVariants() {
        return definition(FieldOptions.forFile(4_000_000L, List.of(), 2, List.of(128)));
    }

    private static CollectionDefinition definition(FieldOptions options) {
        return new CollectionDefinition(
                "budget-test",
                "budget_test",
                List.of(new FieldDefinition("attachment", FieldType.FILE, false, true, options)),
                List.of(),
                null,
                false);
    }

    private static String storedFileId(Document record) {
        Object value = record.get("attachment");
        if (value instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Document first) {
            return first.getString("id");
        }
        if (value instanceof Document single) {
            return single.getString("id");
        }
        throw new IllegalStateException("No stored file in " + value);
    }

    private static long storedFileCount() throws IOException {
        java.nio.file.Path root = Application.getInstance(FileStorageService.class).root();
        if (!java.nio.file.Files.exists(root)) {
            return 0;
        }
        try (var walk = java.nio.file.Files.walk(root)) {
            return walk.filter(java.nio.file.Files::isRegularFile).count();
        }
    }

    /** Simulates heap exhaustion mid-upload: writes after the first throw an Error, not an exception. */
    private static final class HeapExhaustedOnVariant extends FileStorageService {
        private final AtomicInteger stores = new AtomicInteger();

        HeapExhaustedOnVariant(Config config) {
            super(config);
        }

        @Override
        public void store(TenantContext ctx, String fileId, byte[] data) throws IOException {
            if (stores.incrementAndGet() > 1) {
                throw new OutOfMemoryError("Java heap space");
            }
            super.store(ctx, fileId, data);
        }
    }
}
