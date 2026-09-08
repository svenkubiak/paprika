package services;

import jakarta.inject.Singleton;
import org.apache.commons.codec.binary.Base32;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;

@Singleton
public class TwoFactorService {
    private static final String ISSUER = "Paprika";
    private static final int SECRET_BYTES = 20;
    private static final int TIME_STEP_SECONDS = 30;
    private static final int CODE_DIGITS = 6;
    private static final int WINDOW = 1;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Base32 base32 = new Base32();

    public String generateSecret() {
        byte[] buffer = new byte[SECRET_BYTES];
        secureRandom.nextBytes(buffer);
        return base32.encodeToString(buffer).replace("=", "");
    }

    public String buildUri(String username, String secret) {
        String label = URLEncoder.encode(ISSUER + ":" + username, StandardCharsets.UTF_8);
        String encodedSecret = URLEncoder.encode(secret, StandardCharsets.UTF_8);
        String encodedIssuer = URLEncoder.encode(ISSUER, StandardCharsets.UTF_8);
        return "otpauth://totp/" + label + "?secret=" + encodedSecret + "&issuer=" + encodedIssuer + "&digits=6&period=30";
    }

    public boolean verifyCode(String secret, String code) {
        if (secret == null || secret.isBlank() || code == null || !code.trim().matches("\\d{6}")) {
            return false;
        }

        long counter = Instant.now().getEpochSecond() / TIME_STEP_SECONDS;
        String normalized = code.trim();

        for (int offset = -WINDOW; offset <= WINDOW; offset++) {
            if (generateCode(secret, counter + offset).equals(normalized)) {
                return true;
            }
        }

        return false;
    }

    private String generateCode(String secret, long counter) {
        byte[] secretBytes = base32.decode(normalizeSecret(secret));
        byte[] data = ByteBuffer.allocate(8).putLong(counter).array();

        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);

            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);

            int otp = binary % (int) Math.pow(10, CODE_DIGITS);
            return String.format("%0" + CODE_DIGITS + "d", otp);
        } catch (GeneralSecurityException e) {
            return "";
        }
    }

    private String normalizeSecret(String secret) {
        int padding = (8 - (secret.length() % 8)) % 8;
        return secret.toUpperCase() + "=".repeat(padding);
    }
}
