package utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * The EXIF orientation baked into an image variant, checked by where the pixels end up.
 * <p>
 * ImageIO drops the EXIF metadata when it re-encodes, so the rotation has to be applied to the
 * raster before the variant is written - and a wrong affine transform is invisible in every
 * assertion that only looks at the dimensions. Orientation 7 was built from the width and the
 * height the wrong way round and moved the whole image outside the target raster, which produced
 * a variant that was entirely blank. Every orientation is therefore asserted by the colour of its
 * four corners, not by its size.
 * <p>
 * The source image carries one colour per quadrant, so a corner names exactly which part of the
 * original was mapped onto it:
 *
 * <pre>
 *   RED    GREEN
 *   BLUE   WHITE
 * </pre>
 */
class ImageVariantsTest {

    private static final String JPEG = "image/jpeg";
    private static final int SOURCE_WIDTH = 240;
    private static final int SOURCE_HEIGHT = 120;
    private static final int TARGET_WIDTH = 60;

    /** The quadrant colours, in the order {@link #corners} reports them. */
    private static final List<Color> PALETTE = List.of(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE);

    /**
     * Where each orientation has to put the four source quadrants, as the target's corners read
     * top-left, top-right, bottom-left, bottom-right.
     * <p>
     * 1 is the identity, 2/4 mirror, 3 is a half turn, 6/8 are the quarter turns and 5/7 mirror on
     * the two diagonals. The diagonal cases are the ones worth reading twice: 5 (transpose) keeps
     * the top-left and bottom-right corners where they are and swaps the other two, 7 (transverse)
     * does the opposite.
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

    /**
     * The defect itself, stated the way it showed up: the transverse variant was not merely
     * rotated the wrong way, it contained no image at all. A single-colour raster is what
     * "the transform put everything outside the target" looks like from the outside, and the
     * corner assertion above would also catch it - but only this one says why.
     */
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

    /**
     * EXIF only defines 1 to 8. A value outside that range is a broken file, not an instruction,
     * and the unrotated image stays the best answer available.
     */
    @ParameterizedTest(name = "orientation {0}")
    @ValueSource(ints = {0, 9, 255})
    void anOrientationOutsideTheDefinedRangeIsIgnored(int orientation) throws Exception {
        BufferedImage variant = variantOf(jpegWithOrientation(quadrantImage(), orientation));

        assertThat(variant, notNullValue());
        assertThat(corners(variant), equalTo(List.of(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE)));
    }

    /** A malformed APP1 segment must not cost the variant - it is read for the rotation only. */
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

    /** A variant is only ever a smaller copy: an original at or below the target width gets none. */
    @Test
    void anImageThatIsNotWiderThanTheTargetGetsNoVariant() throws Exception {
        assertThat(ImageVariants.scaleToWidth(quadrantImage(), JPEG, SOURCE_WIDTH), nullValue());
        assertThat(ImageVariants.scaleToWidth(quadrantImage(), JPEG, SOURCE_WIDTH + 1), nullValue());
    }

    /**
     * Orientation 6 turns a landscape original into a portrait one, so the width that decides
     * whether a variant is produced at all is the width <em>after</em> the rotation.
     */
    @Test
    void theRotatedWidthDecidesWhetherAVariantIsProduced() throws Exception {
        byte[] rotated = jpegWithOrientation(quadrantImage(), 6);

        // Displayed, the image is 120 wide - so 130 is already too wide to scale down to.
        assertThat(ImageVariants.scaleToWidth(rotated, JPEG, SOURCE_HEIGHT + 10), nullValue());
        assertThat(ImageVariants.scaleToWidth(rotated, JPEG, SOURCE_HEIGHT - 10), notNullValue());
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private static BufferedImage variantOf(byte[] jpeg) throws Exception {
        byte[] variant = ImageVariants.scaleToWidth(jpeg, JPEG, TARGET_WIDTH);
        return variant == null ? null : ImageIO.read(new ByteArrayInputStream(variant));
    }

    /**
     * The four corner quadrants as the palette colour each of them is closest to. Sampled in the
     * middle of a quadrant and matched by nearest neighbour, because JPEG is lossy and the four
     * colours are far enough apart for the nearest one to be unambiguous.
     */
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

    /** One colour per quadrant, so a corner of the variant names the part of the source it came from. */
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

    /** Wraps a JPEG in an APP1/EXIF segment carrying nothing but the orientation tag. */
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
