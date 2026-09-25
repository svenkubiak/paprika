package utils;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Scales an uploaded image down to a configured width, using nothing but the JDK's ImageIO.
 * <p>
 * Only the formats ImageIO can both read and write without an additional library are supported.
 * WebP in particular can be read by newer JDKs but not written, so a WebP upload keeps its
 * original and is always served unscaled - adding an encoder would mean adding a dependency.
 * <p>
 * Variants are produced once, when the file is stored. Scaling on download would let any caller
 * spend server time by asking for arbitrary widths, and would have to be redone on every request.
 */
public final class ImageVariants {

    /** Mime types ImageIO can read <em>and</em> write out of the box. */
    private static final Map<String, String> WRITABLE_FORMATS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/gif", "gif");

    /** Formats that carry no alpha channel and therefore need an opaque target raster. */
    private static final Set<String> OPAQUE_FORMATS = Set.of("jpg");

    /**
     * The largest image that will be decoded, in pixels.
     * <p>
     * {@code maxSize} bounds the <em>compressed</em> bytes, which is precisely what a decompression
     * bomb gets around: the dimensions a file declares in its header cost nothing to write down,
     * but {@code ImageIO.read} allocates the full raster for them - four bytes per pixel - before
     * anything gets a chance to look at how big it turned out. A PNG of uniform colour compresses
     * by a factor in the thousands, so a file well inside the 4 MB transport limit can declare a
     * raster larger than the heap of any ordinary instance.
     * <p>
     * 30 megapixels is ~120 MiB of raster and far above anything that legitimately arrives inside
     * that 4 MB limit: even a well-compressed JPEG needs roughly a byte per three pixels, which
     * puts a real photograph that fits through the upload at well under half this budget.
     */
    public static final long MAX_PIXELS = 30_000_000L;

    private ImageVariants() {
    }

    /**
     * The number of pixels an image declares in its header, without decoding it.
     * <p>
     * {@code ImageReader#getWidth}/{@code #getHeight} read the header only - for PNG that is the
     * 13 byte IHDR chunk - so this answers what a decode would cost before paying it.
     *
     * @return the declared pixel count, or {@code -1} when it cannot be determined (an unsupported
     *         type, or a file no reader will touch - both end up rejected elsewhere)
     */
    public static long declaredPixels(byte[] source, String mimeType) throws IOException {
        if (source == null || !isSupported(mimeType)) {
            return -1;
        }

        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            if (stream == null) {
                return -1;
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return -1;
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                return (long) reader.getWidth(0) * reader.getHeight(0);
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Whether this image is small enough to decode. An image whose size cannot be read is treated
     * as acceptable here - it fails later in the decode, which costs nothing, rather than turning
     * every unreadable file into a rejected upload.
     */
    public static boolean withinPixelBudget(byte[] source, String mimeType) throws IOException {
        long pixels = declaredPixels(source, mimeType);
        return pixels < 0 || pixels <= MAX_PIXELS;
    }

    public static boolean isSupported(String mimeType) {
        return mimeType != null && WRITABLE_FORMATS.containsKey(mimeType.toLowerCase());
    }

    /**
     * Scales {@code source} down to {@code targetWidth}, keeping the aspect ratio.
     *
     * @return the encoded variant, or {@code null} when the image is not wider than the target
     *         (a variant is only ever a smaller copy, never an upscale) or cannot be decoded
     * @throws IOException when reading or writing the image fails
     */
    public static byte[] scaleToWidth(byte[] source, String mimeType, int targetWidth) throws IOException {
        if (source == null || targetWidth <= 0 || !isSupported(mimeType)) {
            return null;
        }

        String format = WRITABLE_FORMATS.get(mimeType.toLowerCase());

        // Checked again here, not only in the caller that validates the upload: this is the line
        // that allocates, and a future caller that forgets the check must not be able to get past
        // it. Reading the header a second time costs a few dozen bytes.
        if (!withinPixelBudget(source, mimeType)) {
            throw new IOException("Refusing to decode an image of " + declaredPixels(source, mimeType)
                    + " pixels, the budget is " + MAX_PIXELS);
        }

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
        if (image == null) {
            return null;
        }

        // ImageIO drops the EXIF metadata when re-encoding, so the orientation has to be baked into
        // the pixels first. Without this a phone photo would be correct as the original and tilted
        // in every variant - a defect that only shows up when someone looks at it.
        image = applyExifOrientation(image, ExifOrientation.of(source, mimeType));

        if (image.getWidth() <= targetWidth) {
            return null;
        }

        int targetHeight = Math.max(1, Math.round(image.getHeight() * (float) targetWidth / image.getWidth()));
        BufferedImage scaled = new BufferedImage(
                targetWidth,
                targetHeight,
                OPAQUE_FORMATS.contains(format) ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);

        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(scaled, format, out)) {
            return null;
        }
        return out.toByteArray();
    }

    private static BufferedImage applyExifOrientation(BufferedImage image, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return image;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        boolean swapsAxes = orientation >= 5;

        AffineTransform transform = switch (orientation) {
            case 2 -> flipHorizontally(width);
            case 3 -> rotate(Math.PI, width, height);
            case 4 -> flipVertically(height);
            case 5 -> transpose();
            case 6 -> rotateQuarterClockwise(height);
            case 7 -> transverse(height, width);
            case 8 -> rotateQuarterCounterClockwise(width);
            default -> null;
        };
        if (transform == null) {
            return image;
        }

        BufferedImage target = new BufferedImage(
                swapsAxes ? height : width,
                swapsAxes ? width : height,
                image.getTransparency() == BufferedImage.OPAQUE
                        ? BufferedImage.TYPE_INT_RGB
                        : BufferedImage.TYPE_INT_ARGB);

        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, transform, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static AffineTransform flipHorizontally(int width) {
        AffineTransform transform = AffineTransform.getScaleInstance(-1, 1);
        transform.translate(-width, 0);
        return transform;
    }

    private static AffineTransform flipVertically(int height) {
        AffineTransform transform = AffineTransform.getScaleInstance(1, -1);
        transform.translate(0, -height);
        return transform;
    }

    private static AffineTransform rotate(double radians, int width, int height) {
        AffineTransform transform = AffineTransform.getRotateInstance(radians);
        transform.translate(-width, -height);
        return transform;
    }

    private static AffineTransform transpose() {
        AffineTransform transform = AffineTransform.getRotateInstance(Math.PI / 2);
        transform.scale(1, -1);
        return transform;
    }

    private static AffineTransform transverse(int height, int width) {
        AffineTransform transform = AffineTransform.getRotateInstance(-Math.PI / 2);
        transform.translate(-height, width);
        transform.scale(1, -1);
        transform.translate(0, -width);
        return transform;
    }

    private static AffineTransform rotateQuarterClockwise(int height) {
        AffineTransform transform = AffineTransform.getRotateInstance(Math.PI / 2);
        transform.translate(0, -height);
        return transform;
    }

    private static AffineTransform rotateQuarterCounterClockwise(int width) {
        AffineTransform transform = AffineTransform.getRotateInstance(-Math.PI / 2);
        transform.translate(-width, 0);
        return transform;
    }

    /**
     * Reads the EXIF orientation tag straight out of the JPEG byte stream. The JDK exposes JPEG
     * metadata only as a raw {@code unknown} marker segment, so the APP1 segment is walked by hand
     * rather than pulling in an EXIF library.
     */
    static final class ExifOrientation {
        private static final int ORIENTATION_TAG = 0x0112;

        private ExifOrientation() {
        }

        static int of(byte[] source, String mimeType) {
            if (source == null || !Strings.CI.equals(mimeType, "image/jpeg")) {
                return 1;
            }
            try {
                return read(source);
            } catch (RuntimeException e) {
                // A malformed EXIF block is not a reason to refuse the variant; the unrotated
                // image is still the best answer available.
                return 1;
            }
        }

        private static int read(byte[] data) {
            int offset = 2; // skip SOI
            while (offset + 4 <= data.length) {
                if ((data[offset] & 0xFF) != 0xFF) {
                    return 1;
                }
                int marker = data[offset + 1] & 0xFF;
                if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                    offset += 2;
                    continue;
                }
                if (marker == 0xDA || marker == 0xD9) {
                    return 1;
                }
                int length = ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
                if (length < 2 || offset + 2 + length > data.length) {
                    return 1;
                }
                if (marker == 0xE1 && isExifHeader(data, offset + 4)) {
                    return readOrientation(data, offset + 10, offset + 2 + length);
                }
                offset += 2 + length;
            }
            return 1;
        }

        private static boolean isExifHeader(byte[] data, int at) {
            return at + 6 <= data.length
                    && data[at] == 'E' && data[at + 1] == 'x' && data[at + 2] == 'i' && data[at + 3] == 'f'
                    && data[at + 4] == 0 && data[at + 5] == 0;
        }

        private static int readOrientation(byte[] data, int tiffStart, int end) {
            if (tiffStart + 8 > end) {
                return 1;
            }
            boolean bigEndian = data[tiffStart] == 'M' && data[tiffStart + 1] == 'M';
            boolean littleEndian = data[tiffStart] == 'I' && data[tiffStart + 1] == 'I';
            if (!bigEndian && !littleEndian) {
                return 1;
            }

            int ifdOffset = (int) readUnsigned(data, tiffStart + 4, 4, bigEndian);
            int ifd = tiffStart + ifdOffset;
            if (ifd + 2 > end) {
                return 1;
            }

            int entries = (int) readUnsigned(data, ifd, 2, bigEndian);
            for (int i = 0; i < entries; i++) {
                int entry = ifd + 2 + i * 12;
                if (entry + 12 > end) {
                    return 1;
                }
                if (readUnsigned(data, entry, 2, bigEndian) == ORIENTATION_TAG) {
                    return (int) readUnsigned(data, entry + 8, 2, bigEndian);
                }
            }
            return 1;
        }

        private static long readUnsigned(byte[] data, int at, int length, boolean bigEndian) {
            long value = 0;
            for (int i = 0; i < length; i++) {
                int index = bigEndian ? at + i : at + length - 1 - i;
                if (index < 0 || index >= data.length) {
                    return 0;
                }
                value = (value << 8) | (data[index] & 0xFF);
            }
            return value;
        }
    }
}
