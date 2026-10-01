package utils;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * A uniform 20 MP JPEG is tiny on disk but decodes to ~140 MiB per width without subsampling. Measured
 * as bytes allocated by this thread, which counts every raster regardless of GC.
 */
class ImageVariantDecodeCostTest {
    private static final int STORED_WIDTH = 5_000;
    private static final int STORED_HEIGHT = 4_000;
    private static final long BUDGET_BYTES = 64L * 1024 * 1024;

    private static byte[] rotatedJpeg;

    @BeforeAll
    static void createImage() throws IOException {
        BufferedImage image = new BufferedImage(STORED_WIDTH, STORED_HEIGHT, BufferedImage.TYPE_3BYTE_BGR);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        // Orientation 6: stored 5000x4000, shown 4000x5000
        rotatedJpeg = withOrientation(out.toByteArray(), 6);
    }

    @Test
    void aVariantOfALargeRotatedJpegStaysWithinASmallHeapBudget() throws IOException {
        long before = allocatedBytes();
        byte[] variant = ImageVariants.scaleToWidth(rotatedJpeg, "image/jpeg", 1024);
        long allocated = allocatedBytes() - before;

        assertThat(variant, notNullValue());
        assertThat("allocated " + (allocated >> 20) + " MiB for one variant", allocated, lessThan(BUDGET_BYTES));
    }

    @Test
    void theVariantIsUprightAndScaledToTheShownWidth() throws IOException {
        BufferedImage variant = ImageIO.read(new ByteArrayInputStream(
                ImageVariants.scaleToWidth(rotatedJpeg, "image/jpeg", 1024)));

        assertThat(variant.getWidth(), is(1024));
        assertThat(variant.getHeight(), is(1280));
    }

    private static long allocatedBytes() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
                .getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    private static byte[] withOrientation(byte[] jpeg, int orientation) {
        byte[] tiff = new byte[] {
                'M', 'M', 0, 42, 0, 0, 0, 8,
                0, 1,
                0x01, 0x12,
                0, 3,
                0, 0, 0, 1,
                (byte) ((orientation >> 8) & 0xFF), (byte) (orientation & 0xFF), 0, 0,
                0, 0, 0, 0
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

        byte[] result = new byte[jpeg.length + segment.length];
        result[0] = jpeg[0];
        result[1] = jpeg[1];
        System.arraycopy(segment, 0, result, 2, segment.length);
        System.arraycopy(jpeg, 2, result, 2 + segment.length, jpeg.length - 2);
        return result;
    }
}
