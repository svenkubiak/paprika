package services;

import auth.TenantContext;
import enums.FieldType;
import models.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import utils.MultipartSupport;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class ImageVariantServiceTest {

    @TempDir
    Path storageRoot;

    private ImageVariantQueue queue;

    private FileFieldService service(FileStorageService storage) {
        queue = new ImageVariantQueue(storage);
        return new FileFieldService(storage, queue);
    }

    private void awaitVariants() throws InterruptedException {
        assertThat("the variant queue did not drain", queue.awaitIdle(Duration.ofSeconds(10)), is(true));
    }

    @Test
    void variantsAreCreatedForEveryConfiguredWidthThatIsSmallerThanTheOriginal() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot);
        FileFieldService service = service(storage);
        TenantContext ctx = TenantContext.guest("tenant-1", "database");

        FileFieldService.UploadChanges changes = upload(
                service, ctx, List.of(100, 200, 900), image(400, 200, "png"), "image/png", "picture.png");

        String fileId = changes.storedFileIds().getFirst();
        // 900 is wider than the original: a variant is only ever a smaller copy.
        assertThat(storage.variantWidths(ctx, fileId), contains(100, 200));
        assertThat(widthOf(storage.read(ctx, FileStorageService.variantKey(fileId, 100))), is(100));
        assertThat(widthOf(storage.read(ctx, FileStorageService.variantKey(fileId, 200))), is(200));
        assertThat(widthOf(storage.read(ctx, fileId)), is(400));
    }

    @Test
    void aTypeImageIoCannotWriteKeepsOnlyItsOriginal() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot);
        FileFieldService service = service(storage);
        TenantContext ctx = TenantContext.guest("tenant-1", "database");

        // image/webp can be read by the JDK but not written, so no variant can exist for it.
        FileFieldService.UploadChanges changes = upload(
                service, ctx, List.of(100), "not really an image".getBytes(StandardCharsets.UTF_8),
                "image/webp", "picture.webp");

        String fileId = changes.storedFileIds().getFirst();
        assertThat(storage.variantWidths(ctx, fileId), is(empty()));
        assertThat(storage.read(ctx, fileId), notNullValue());
    }

    @Test
    void anUnreadableImageDoesNotFailTheUpload() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot);
        FileFieldService service = service(storage);
        TenantContext ctx = TenantContext.guest("tenant-1", "database");

        FileFieldService.UploadChanges changes = upload(
                service, ctx, List.of(100), "this is not a png".getBytes(StandardCharsets.UTF_8),
                "image/png", "broken.png");

        String fileId = changes.storedFileIds().getFirst();
        assertThat(storage.read(ctx, fileId), notNullValue());
        assertThat(storage.variantWidths(ctx, fileId), is(empty()));
    }

    @Test
    void anExifRotatedJpegIsUprightInTheVariant() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot);
        FileFieldService service = service(storage);
        TenantContext ctx = TenantContext.guest("tenant-1", "database");

        // Orientation 6 rotates 90 degrees clockwise for display, so the 400x200 source is shown as 200x400.
        byte[] rotated = jpegWithOrientation(image(400, 200, "jpg"), 6);

        FileFieldService.UploadChanges changes =
                upload(service, ctx, List.of(100), rotated, "image/jpeg", "photo.jpg");

        String fileId = changes.storedFileIds().getFirst();
        BufferedImage variant = ImageIO.read(
                new ByteArrayInputStream(storage.read(ctx, FileStorageService.variantKey(fileId, 100))));
        assertThat(variant.getWidth(), is(100));
        assertThat(variant.getHeight(), is(200));
    }

    @Test
    void deletingTheFileDeletesItsVariants() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot);
        FileFieldService service = service(storage);
        TenantContext ctx = TenantContext.guest("tenant-1", "database");

        FileFieldService.UploadChanges changes =
                upload(service, ctx, List.of(100), image(400, 200, "png"), "image/png", "picture.png");
        String fileId = changes.storedFileIds().getFirst();
        assertThat(storage.variantWidths(ctx, fileId), contains(100));

        storage.delete(ctx, fileId);

        assertThat(storage.read(ctx, fileId), is(nullValue()));
        assertThat(storage.variantWidths(ctx, fileId), is(empty()));
        assertThat(storage.read(ctx, FileStorageService.variantKey(fileId, 100)), is(nullValue()));
    }

    @Test
    void rollingBackAnUploadDeletesItsVariants() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot);
        FileFieldService service = service(storage);
        TenantContext ctx = TenantContext.guest("tenant-1", "database");

        // Staged, not committed: the record write that would have pointed at it failed
        FileFieldService.UploadChanges changes =
                stage(service, ctx, List.of(100), image(400, 200, "png"), "image/png", "picture.png");
        String fileId = changes.storedFileIds().getFirst();

        service.rollbackUploads(ctx, changes);
        awaitVariants();

        assertThat(storage.read(ctx, fileId), is(nullValue()));
        assertThat("a rolled back upload queues no decode", storage.variantWidths(ctx, fileId), is(empty()));
    }

    @Test
    void replacingAFileDeletesTheVariantsOfTheReplacedOne() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot);
        FileFieldService service = service(storage);
        TenantContext ctx = TenantContext.guest("tenant-1", "database");

        FileFieldService.UploadChanges first =
                upload(service, ctx, List.of(100), image(400, 200, "png"), "image/png", "first.png");
        String oldId = first.storedFileIds().getFirst();

        Document record = new Document(
                "attachment",
                new FileReference(oldId, "first.png", "image/png", 1).toDocument());
        FileFieldService.UploadChanges second = service.applyUploads(
                ctx,
                definition(List.of(100)),
                record,
                Map.of("attachment", List.of(new MultipartSupport.UploadedFile(
                        "second.png", image(400, 200, "png"), "image/png"))),
                false);
        service.commitUploads(ctx, second);
        awaitVariants();

        assertThat(storage.read(ctx, oldId), is(nullValue()));
        assertThat(storage.variantWidths(ctx, oldId), is(empty()));
        assertThat(storage.variantWidths(ctx, second.storedFileIds().getFirst()), contains(100));
    }

    @Test
    void deletingTheRecordDeletesTheVariants() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot);
        FileFieldService service = service(storage);
        TenantContext ctx = TenantContext.guest("tenant-1", "database");

        Document record = new Document();
        CollectionDefinition definition = definition(List.of(100));
        FileFieldService.UploadChanges changes = service.applyUploads(
                ctx,
                definition,
                record,
                Map.of("attachment", List.of(new MultipartSupport.UploadedFile(
                        "picture.png", image(400, 200, "png"), "image/png"))),
                false);
        service.commitUploads(ctx, changes);
        awaitVariants();
        String fileId = changes.storedFileIds().getFirst();

        service.deleteRecordFiles(ctx, definition, record);

        assertThat(storage.read(ctx, fileId), is(nullValue()));
        assertThat(storage.variantWidths(ctx, fileId), is(empty()));
    }

    @Test
    void readingFallsBackToTheNextLargerVariantAndThenToTheOriginal() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot);
        FileFieldService service = service(storage);
        TenantContext ctx = TenantContext.guest("tenant-1", "database");

        FileFieldService.UploadChanges changes = upload(
                service, ctx, List.of(100, 300), image(800, 400, "png"), "image/png", "picture.png");
        FileReference reference =
                new FileReference(changes.storedFileIds().getFirst(), "picture.png", "image/png", 1);

        FileFieldService.VariantDelivery exact = service.readFile(ctx, reference, 100);
        assertThat(exact.width(), is(100));
        assertThat(widthOf(exact.bytes()), is(100));

        FileFieldService.VariantDelivery larger = service.readFile(ctx, reference, 150);
        assertThat(larger.width(), is(300));

        FileFieldService.VariantDelivery original = service.readFile(ctx, reference, 5000);
        assertThat(original.width(), is(nullValue()));
        assertThat(widthOf(original.bytes()), is(800));

        FileFieldService.VariantDelivery untouched = service.readFile(ctx, reference, null);
        assertThat(untouched.width(), is(nullValue()));
        assertThat(widthOf(untouched.bytes()), is(800));
    }

    private FileFieldService.UploadChanges upload(
            FileFieldService service,
            TenantContext ctx,
            List<Integer> widths,
            byte[] bytes,
            String mimeType,
            String fileName) throws Exception {

        FileFieldService.UploadChanges changes = stage(service, ctx, widths, bytes, mimeType, fileName);
        service.commitUploads(ctx, changes);
        awaitVariants();
        return changes;
    }

    private static FileFieldService.UploadChanges stage(
            FileFieldService service,
            TenantContext ctx,
            List<Integer> widths,
            byte[] bytes,
            String mimeType,
            String fileName) throws Exception {

        return service.applyUploads(
                ctx,
                definition(widths),
                new Document(),
                Map.of("attachment", List.of(new MultipartSupport.UploadedFile(fileName, bytes, mimeType))),
                false);
    }

    private static CollectionDefinition definition(List<Integer> widths) {
        return new CollectionDefinition(
                "definition-id",
                "documents",
                List.of(new FieldDefinition(
                        "attachment",
                        FieldType.FILE,
                        false,
                        true,
                        FieldOptions.forFile(5L * 1024 * 1024, List.of(), 1, widths))),
                List.of(),
                CollectionRules.locked(),
                false);
    }

    private static int widthOf(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes)).getWidth();
    }

    private static byte[] image(int width, int height, String format) throws Exception {
        BufferedImage image = new BufferedImage(
                width, height, "png".equals(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, width / 4, height / 4);
        graphics.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static byte[] jpegWithOrientation(byte[] jpeg, int orientation) {
        byte[] exif = exifSegment(orientation);
        byte[] result = new byte[jpeg.length + exif.length];
        // SOI, then the APP1 segment, then the rest of the original stream.
        result[0] = jpeg[0];
        result[1] = jpeg[1];
        System.arraycopy(exif, 0, result, 2, exif.length);
        System.arraycopy(jpeg, 2, result, 2 + exif.length, jpeg.length - 2);
        return result;
    }

    private static byte[] exifSegment(int orientation) {
        byte[] tiff = new byte[] {
                'M', 'M', 0, 42, 0, 0, 0, 8,           // big endian header, IFD at offset 8
                0, 1,                                   // one entry
                0x01, 0x12,                             // orientation tag
                0, 3,                                   // type SHORT
                0, 0, 0, 1,                             // one value
                (byte) ((orientation >> 8) & 0xFF), (byte) (orientation & 0xFF), 0, 0,
                0, 0, 0, 0                              // next IFD: none
        };
        byte[] header = new byte[] {'E', 'x', 'i', 'f', 0, 0};
        int length = 2 + header.length + tiff.length;

        byte[] segment = new byte[2 + length];
        segment[0] = (byte) 0xFF;
        segment[1] = (byte) 0xE1;
        segment[2] = (byte) ((length >> 8) & 0xFF);
        segment[3] = (byte) (length & 0xFF);
        System.arraycopy(header, 0, segment, 4, header.length);
        System.arraycopy(tiff, 0, segment, 4 + header.length, tiff.length);
        return segment;
    }
}
