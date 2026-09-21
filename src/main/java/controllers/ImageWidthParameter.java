package controllers;

/**
 * Parses the optional {@code width} query parameter of a file download.
 * <p>
 * A value that is not a positive number is a client mistake and answered with {@code 400}. A width
 * that is simply not configured on the field is <em>not</em> a mistake: the download falls back to
 * the next larger variant or the original, and names what it delivered in a response header.
 */
final class ImageWidthParameter {

    static final class InvalidImageWidthException extends RuntimeException {
        InvalidImageWidthException(String message) {
            super(message);
        }
    }

    private ImageWidthParameter() {
    }

    static Integer parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int width = Integer.parseInt(raw.trim());
            if (width <= 0) {
                throw new InvalidImageWidthException("width must be greater than 0");
            }
            return width;
        } catch (NumberFormatException e) {
            throw new InvalidImageWidthException("width must be a positive number");
        }
    }
}
