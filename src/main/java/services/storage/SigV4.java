package services.storage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** AWS Signature Version 4 for the {@code s3} service. One shot per request; {@link Mac} is not reused. */
public final class SigV4 {
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SCHEME = "AWS4";
    private static final String TERMINATOR = "aws4_request";
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final char[] HEX_UPPER = "0123456789ABCDEF".toCharArray();

    private SigV4() {
    }

    public static String emptyPayloadHash() {
        return sha256Hex(new byte[0]);
    }

    public static String sha256Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * @param amzDate {@code yyyyMMdd'T'HHmmss'Z'}
     * @return the value of the {@code Authorization} header
     */
    public static String authorization(
            String accessKey,
            String secret,
            String region,
            String amzDate,
            String canonicalRequest,
            String signedHeaders) {

        Objects.requireNonNull(accessKey, "accessKey must not be null");
        Objects.requireNonNull(secret, "secret must not be null");
        String date = amzDate.substring(0, 8);
        String scope = date + "/" + region + "/s3/" + TERMINATOR;
        String signature = signature(secret, region, amzDate, canonicalRequest);
        return ALGORITHM + " Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;
    }

    static String signature(String secret, String region, String amzDate, String canonicalRequest) {
        String date = amzDate.substring(0, 8);
        String scope = date + "/" + region + "/s3/" + TERMINATOR;
        String toSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] key = hmac(hmac(hmac(hmac((SCHEME + secret).getBytes(StandardCharsets.UTF_8), date), region), "s3"), TERMINATOR);
        return hex(hmac(key, toSign));
    }

    /** Percent-encode for SigV4. {@code encodeSlash} is false for a path, where {@code /} separates segments. */
    public static String encode(String value, boolean encodeSlash) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            if (isUnreserved(b) || (b == '/' && !encodeSlash)) {
                encoded.append((char) b);
            } else {
                encoded.append('%').append(HEX_UPPER[(b >> 4) & 0xF]).append(HEX_UPPER[b & 0xF]);
            }
        }
        return encoded.toString();
    }

    public static String hex(byte[] data) {
        char[] chars = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int value = data[i] & 0xFF;
            chars[i * 2] = HEX[value >>> 4];
            chars[i * 2 + 1] = HEX[value & 0xF];
        }
        return new String(chars);
    }

    private static boolean isUnreserved(byte b) {
        return (b >= 'A' && b <= 'Z')
                || (b >= 'a' && b <= 'z')
                || (b >= '0' && b <= '9')
                || b == '-' || b == '_' || b == '.' || b == '~';
    }

    private static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
