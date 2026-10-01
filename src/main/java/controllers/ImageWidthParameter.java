package controllers;

// An unconfigured width is not an error: the download falls back to the next larger variant.
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
