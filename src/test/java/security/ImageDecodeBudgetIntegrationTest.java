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
 * Executable specification of the pixel budget on decoded uploads.
 * <p>
 * {@code maxSize} bounds the compressed bytes of an upload, which is exactly what a decompression
 * bomb sidesteps: the dimensions in an image header cost nothing to write down, and
 * {@code ImageIO.read} allocates four bytes per declared pixel before anything can look at how
 * large the result is. A file comfortably inside the 4 MB transport limit can therefore ask for a
 * raster bigger than the heap, and the {@code catch (IOException | RuntimeException)} around the
 * variant code does not catch the {@code OutOfMemoryError} that follows - it is an {@code Error}.
 * <p>
 * The budget is checked against the header, before the decode and before the first byte reaches
 * storage. The oversized image used here is an ordinary one-bit-per-pixel bitmap: genuinely that
 * many pixels, cheap to hold in the test, and it exercises the same check a hostile file would.
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

        // The guard sits on the line that allocates, not only in the caller, so a future caller
        // that forgets to validate cannot walk past it
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
        // The good file is listed first on purpose: validation runs over the whole field before
        // the storing loop starts, so neither of them may end up on disk.
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
    void ordinaryImagesStillGetTheirVariants() throws IOException {
        FileFieldService files = Application.getInstance(FileFieldService.class);
        FileStorageService storage = Application.getInstance(FileStorageService.class);
        TenantContext ctx = TenantTestUtils.defaultTenantContext();

        Document record = new Document();
        files.applyUploads(
                ctx,
                definitionWithVariants(),
                record,
                Map.of("attachment", List.of(
                        new MultipartSupport.UploadedFile("ok.png", onePixelPerBitPng(512, 512), "image/png"))),
                false);

        assertThat("the budget must not break ordinary uploads", record.get("attachment"), notNullValue());

        String fileId = storedFileId(record);
        assertThat("a 512 px image still gets its 128 px variant",
                storage.variantWidths(ctx, fileId), hasItem(128));
    }

    @Test
    void aFieldWithoutVariantsIsNotAffected() throws IOException {
        FileFieldService files = Application.getInstance(FileFieldService.class);
        TenantContext ctx = TenantTestUtils.defaultTenantContext();

        // Nothing decodes this upload, so its dimensions are nobody's business - it is bytes like
        // any other file. Refusing it here would be a limit the vulnerability never justified.
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
    void aHardFailureWhileScalingLeavesNothingBehind() throws IOException {
        // The budget refuses the images that would exhaust the heap, but it cannot promise the
        // decode never fails hard for another reason - a smaller heap, a format the budget cannot
        // read a size from, memory pressure from elsewhere. When it does, the original is already
        // in storage and no record will ever point at it. The cleanup has to cover Error too.
        FileFieldService files = new FileFieldService(
                new HeapExhaustedOnVariant(Application.getInstance(Config.class)));
        TenantContext ctx = TenantTestUtils.defaultTenantContext();

        long before = storedFileCount();

        Document record = new Document();
        assertThrows(OutOfMemoryError.class, () -> files.applyUploads(
                ctx,
                definitionWithVariants(),
                record,
                Map.of("attachment", List.of(
                        new MultipartSupport.UploadedFile("ok.png", onePixelPerBitPng(512, 512), "image/png"))),
                false));

        assertThat("the original must not survive a failure that leaves no record behind",
                storedFileCount(), equalTo(before));
    }

    // ---------------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------------

    /**
     * A real, valid PNG of the given size, stored one bit per pixel. That keeps the test's own
     * memory in the single-digit megabytes while the file honestly declares every one of those
     * pixels - which is what the budget is read against.
     */
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

    /** Files below the storage root, as a before/after measure for "nothing was written". */
    private static long storedFileCount() throws IOException {
        java.nio.file.Path root = Application.getInstance(FileStorageService.class).root();
        if (!java.nio.file.Files.exists(root)) {
            return 0;
        }
        try (var walk = java.nio.file.Files.walk(root)) {
            return walk.filter(java.nio.file.Files::isRegularFile).count();
        }
    }

    /**
     * Stands in for the decode that exhausts the heap: the original is written, every scaled copy
     * after it fails with an {@code Error} - which is exactly what the variant code's
     * {@code catch (IOException | RuntimeException)} does not catch.
     */
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
