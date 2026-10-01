package utils;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// JDK ImageIO only: WebP can be read but not written, so WebP is served unscaled.
// Variants are produced once after storing; scaling on download would let callers burn CPU.
public final class ImageVariants {

    private static final Map<String, String> WRITABLE_FORMATS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/gif", "gif");

    private static final Set<String> OPAQUE_FORMATS = Set.of("jpg");

    // Decompression-bomb guard: maxSize only bounds compressed bytes, but ImageIO allocates the full
    // raster (4 bytes/pixel) the header declares. 30 MP (~120 MiB) is far above any real 4 MB upload.
    public static final long MAX_PIXELS = 30_000_000L;

    private ImageVariants() {
    }

    // Reads the header only, so it answers what a decode would cost before paying it; -1 if unknown
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

    // An unreadable size passes here; such a file fails cheaply in the decode instead
    public static boolean withinPixelBudget(byte[] source, String mimeType) throws IOException {
        long pixels = declaredPixels(source, mimeType);
        return pixels < 0 || pixels <= MAX_PIXELS;
    }

    public static boolean isSupported(String mimeType) {
        return mimeType != null && WRITABLE_FORMATS.containsKey(mimeType.toLowerCase());
    }

    // Returns null when the image is not wider than the target: never upscales
    public static byte[] scaleToWidth(byte[] source, String mimeType, int targetWidth) throws IOException {
        return scaleToWidths(source, mimeType, List.of(targetWidth)).get(targetWidth);
    }

    // Single decode with source subsampling, so the raster is only as wide as the widest variant.
    // EXIF orientation is applied after scaling to avoid a second full-size raster.
    public static Map<Integer, byte[]> scaleToWidths(byte[] source, String mimeType, List<Integer> targetWidths)
            throws IOException {

        if (source == null || targetWidths == null || !isSupported(mimeType)) {
            return Map.of();
        }

        String format = WRITABLE_FORMATS.get(mimeType.toLowerCase());

        // Re-checked here because this is where the allocation happens; callers may forget the check
        if (!withinPixelBudget(source, mimeType)) {
            throw new IOException("Refusing to decode an image of " + declaredPixels(source, mimeType)
                    + " pixels, the budget is " + MAX_PIXELS);
        }

        // ImageIO drops EXIF on re-encode, so the orientation has to be baked into the pixels
        int orientation = ExifOrientation.of(source, mimeType);
        boolean swapsAxes = orientation >= 5 && orientation <= 8;

        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            if (stream == null) {
                return Map.of();
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return Map.of();
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int displayWidth = swapsAxes ? reader.getHeight(0) : reader.getWidth(0);

                List<Integer> widths = targetWidths.stream()
                        .filter(Objects::nonNull)
                        .filter(width -> width > 0 && width < displayWidth)
                        .distinct()
                        .sorted(Comparator.reverseOrder())
                        .toList();
                if (widths.isEmpty()) {
                    return Map.of();
                }

                int subsampling = Math.max(1, displayWidth / widths.getFirst());
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(subsampling, subsampling, 0, 0);
                BufferedImage decoded = reader.read(0, param);

                Map<Integer, byte[]> variants = new LinkedHashMap<>();
                for (int width : widths) {
                    BufferedImage variant = applyExifOrientation(
                            scaleForDisplayWidth(decoded, width, swapsAxes, format), orientation);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    if (ImageIO.write(variant, format, out)) {
                        variants.put(width, out.toByteArray());
                    }
                }
                return variants;
            } finally {
                reader.dispose();
            }
        }
    }

    // Scales in stored orientation to a size that is displayWidth wide after EXIF rotation
    private static BufferedImage scaleForDisplayWidth(
            BufferedImage image,
            int displayWidth,
            boolean swapsAxes,
            String format) {

        int sourceDisplayWidth = swapsAxes ? image.getHeight() : image.getWidth();
        int sourceDisplayHeight = swapsAxes ? image.getWidth() : image.getHeight();
        int displayHeight = Math.max(1, Math.round(sourceDisplayHeight * (float) displayWidth / sourceDisplayWidth));

        BufferedImage scaled = new BufferedImage(
                swapsAxes ? displayHeight : displayWidth,
                swapsAxes ? displayWidth : displayHeight,
                OPAQUE_FORMATS.contains(format) ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);

        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(image, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
        } finally {
            graphics.dispose();
        }
        return scaled;
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
            case 7 -> transverse(width, height);
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

    // Transpose then half turn inside the transposed raster (height wide, width high), so the
    // translation is (height, width); swapping them yields a blank variant.
    private static AffineTransform transverse(int width, int height) {
        AffineTransform transform = AffineTransform.getTranslateInstance(height, width);
        transform.scale(-1, -1);
        transform.concatenate(transpose());
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

    // The JDK exposes JPEG EXIF only as a raw marker segment, so APP1 is parsed by hand
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
                // A malformed EXIF block must not prevent the variant
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
