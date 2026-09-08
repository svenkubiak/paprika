package utils;

import org.apache.commons.lang3.StringUtils;
import org.apache.tika.Tika;

import java.util.List;
import java.util.Locale;

public final class MimeTypes {
    private static final Tika TIKA = new Tika();

    private MimeTypes() {
    }

    public static String detect(byte[] data, String fallbackFileName) {
        if (data == null || data.length == 0) {
            return guessFromFileName(fallbackFileName);
        }

        return TIKA.detect(data, fallbackFileName);
    }

    public static boolean matches(String mimeType, List<String> allowed) {
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        if (StringUtils.isBlank(mimeType)) {
            return false;
        }
        String normalized = mimeType.toLowerCase(Locale.ROOT);
        for (String pattern : allowed) {
            if (StringUtils.isBlank(pattern)) {
                continue;
            }
            String candidate = pattern.trim().toLowerCase(Locale.ROOT);
            if (candidate.endsWith("/*")) {
                String prefix = candidate.substring(0, candidate.length() - 1);
                if (normalized.startsWith(prefix)) {
                    return true;
                }
            } else if (candidate.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static String guessFromFileName(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return "application/octet-stream";
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        return "application/octet-stream";
    }
}
