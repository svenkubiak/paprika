package hooks;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public final class HookSignature {
    private static final String ALGORITHM = "HmacSHA256";

    private HookSignature() {
    }

    public static String sign(String secret, String payload) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Hook signing secret is required");
        }

        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign hook payload", e);
        }
    }
}
