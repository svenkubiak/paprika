package utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * ImageIO drops EXIF on re-encode, so the rotation is baked into the raster; a wrong transform keeps
 * the dimensions, so corners are asserted by colour. Source quadrants: RED GREEN / BLUE WHITE.
 */
class ImageVariantsTest {

    private static final String JPEG = "image/jpeg";
    private static final int SOURCE_WIDTH = 240;
    private static final int SOURCE_HEIGHT = 120;
    private static final int TARGET_WIDTH = 60;

    private static final List<Color> PALETTE = List.of(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE);

    /**
     * Corners read top-left, top-right, bottom-left, bottom-right. 5 (transpose) keeps top-left and
     * bottom-right and swaps the other two, 7 (transverse) does the opposite.
     */
    private static Stream<Arguments> orientations() {
        return Stream.of(
                Arguments.of(1, List.of(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE), false),
                Arguments.of(2, List.of(Color.GREEN, Color.RED, Color.WHITE, Color.BLUE), false),
                Arguments.of(3, List.of(Color.WHITE, Color.BLUE, Color.GREEN, Color.RED), false),
                Arguments.of(4, List.of(Color.BLUE, Color.WHITE, Color.RED, Color.GREEN), false),
                Arguments.of(5, List.of(Color.RED, Color.BLUE, Color.GREEN, Color.WHITE), true),
                Arguments.of(6, List.of(Color.BLUE, Color.RED, Color.WHITE, Color.GREEN), true),
                Arguments.of(7, List.of(Color.WHITE, Color.GREEN, Color.BLUE, Color.RED), true),
                Arguments.of(8, List.of(Color.GREEN, Color.WHITE, Color.RED, Color.BLUE), true));
    }

    @ParameterizedTest(name = "orientation {0}")
    @MethodSource("orientations")
    void everyExifOrientationIsBakedIntoTheVariant(
            int orientation, List<Color> expectedCorners, boolean swapsAxes) throws Exception {

        BufferedImage variant = variantOf(jpegWithOrientation(quadrantImage(), orientation));

        assertThat(variant, notNullValue());
        assertThat(variant.getWidth(), is(TARGET_WIDTH));
        assertThat(
                variant.getHeight(),
                is(swapsAxes
                        ? TARGET_WIDTH * SOURCE_WIDTH / SOURCE_HEIGHT
                        : TARGET_WIDTH * SOURCE_HEIGHT / SOURCE_WIDTH));
        assertThat(corners(variant), equalTo(expectedCorners));
    }

    @Test
    void theTransverseOrientationDoesNotProduceABlankVariant() throws Exception {
        BufferedImage variant = variantOf(jpegWithOrientation(quadrantImage(), 7));

        assertThat(variant, notNullValue());
        assertThat(distinctColours(variant), greaterThan(1));
    }

    @Test
    void aJpegWithoutAnExifSegmentIsLeftAsItIs() throws Exception {
        BufferedImage variant = variantOf(quadrantImage());

        assertThat(variant, notNullValue());
        assertThat(variant.getWidth(), is(TARGET_WIDTH));
        assertThat(corners(variant), equalTo(List.of(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE)));
    }

    /** EXIF only defines 1 to 8; anything else is a broken file and the unrotated image is kept. */
    @ParameterizedTest(name = "orientation {0}")
    @ValueSource(ints = {0, 9, 255})
    void anOrientationOutsideTheDefinedRangeIsIgnored(int orientation) throws Exception {
        BufferedImage variant = variantOf(jpegWithOrientation(quadrantImage(), orientation));

        assertThat(variant, notNullValue());
        assertThat(corners(variant), equalTo(List.of(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE)));
    }

    @Test
    void aTruncatedExifSegmentDoesNotFailTheVariant() throws Exception {
        byte[] withExif = jpegWithOrientation(quadrantImage(), 7);
        // Blank out the TIFF byte order marker, which is as far as the reader gets before giving up.
        byte[] broken = withExif.clone();
        int tiffStart = indexOfExifHeader(broken) + 6;
        broken[tiffStart] = 'X';
        broken[tiffStart + 1] = 'X';

        BufferedImage variant = variantOf(broken);

        assertThat(variant, notNullValue());
        assertThat(corners(variant), equalTo(List.of(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE)));
    }

    @Test
    void anImageThatIsNotWiderThanTheTargetGetsNoVariant() throws Exception {
        assertThat(ImageVariants.scaleToWidth(quadrantImage(), JPEG, SOURCE_WIDTH), nullValue());
        assertThat(ImageVariants.scaleToWidth(quadrantImage(), JPEG, SOURCE_WIDTH + 1), nullValue());
    }

    @Test
    void theRotatedWidthDecidesWhetherAVariantIsProduced() throws Exception {
        byte[] rotated = jpegWithOrientation(quadrantImage(), 6);

        // Displayed, the image is 120 wide - so 130 is already too wide to scale down to.
        assertThat(ImageVariants.scaleToWidth(rotated, JPEG, SOURCE_HEIGHT + 10), nullValue());
        assertThat(ImageVariants.scaleToWidth(rotated, JPEG, SOURCE_HEIGHT - 10), notNullValue());
    }

    private static BufferedImage variantOf(byte[] jpeg) throws Exception {
        byte[] variant = ImageVariants.scaleToWidth(jpeg, JPEG, TARGET_WIDTH);
        return variant == null ? null : ImageIO.read(new ByteArrayInputStream(variant));
    }

    /** Sampled mid-quadrant and matched to the nearest palette colour, because JPEG is lossy. */
    private static List<Color> corners(BufferedImage image) {
        int left = image.getWidth() / 4;
        int right = image.getWidth() * 3 / 4;
        int top = image.getHeight() / 4;
        int bottom = image.getHeight() * 3 / 4;

        return List.of(
                nearestPaletteColour(image.getRGB(left, top)),
                nearestPaletteColour(image.getRGB(right, top)),
                nearestPaletteColour(image.getRGB(left, bottom)),
                nearestPaletteColour(image.getRGB(right, bottom)));
    }

    private static Color nearestPaletteColour(int rgb) {
        Color actual = new Color(rgb);
        Color nearest = PALETTE.getFirst();
        long best = Long.MAX_VALUE;

        for (Color candidate : PALETTE) {
            long distance = squared(actual.getRed() - candidate.getRed())
                    + squared(actual.getGreen() - candidate.getGreen())
                    + squared(actual.getBlue() - candidate.getBlue());
            if (distance < best) {
                best = distance;
                nearest = candidate;
            }
        }

        return nearest;
    }

    private static long squared(int value) {
        return (long) value * value;
    }

    private static int distinctColours(BufferedImage image) {
        return (int) PALETTE.stream()
                .filter(colour -> containsColour(image, colour))
                .count();
    }

    private static boolean containsColour(BufferedImage image, Color colour) {
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if (nearestPaletteColour(image.getRGB(x, y)).equals(colour)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] quadrantImage() throws Exception {
        BufferedImage image = new BufferedImage(SOURCE_WIDTH, SOURCE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        int halfWidth = SOURCE_WIDTH / 2;
        int halfHeight = SOURCE_HEIGHT / 2;

        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, halfWidth, halfHeight);
        graphics.setColor(Color.GREEN);
        graphics.fillRect(halfWidth, 0, halfWidth, halfHeight);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, halfHeight, halfWidth, halfHeight);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(halfWidth, halfHeight, halfWidth, halfHeight);
        graphics.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
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

    private static int indexOfExifHeader(byte[] data) {
        for (int i = 0; i + 4 < data.length; i++) {
            if (data[i] == 'E' && data[i + 1] == 'x' && data[i + 2] == 'i' && data[i + 3] == 'f') {
                return i;
            }
        }
        throw new IllegalStateException("No EXIF header in the test image");
    }
}
