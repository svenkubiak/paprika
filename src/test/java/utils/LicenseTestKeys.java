package utils;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;

/** Issues license keys the way the licensor's signing tool does, with throwaway key pairs. */
public final class LicenseTestKeys {
    private LicenseTestKeys() {
    }

    public static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sign(String json, PrivateKey privateKey) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        String signedPart = Licenses.PREFIX + "." + payload;
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(signedPart.getBytes(StandardCharsets.US_ASCII));
            return signedPart + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String payload(String licensee, int installations, String expiresAt) {
        return "{\"licenseId\":\"PL-TEST-0001\",\"licensee\":\"" + licensee + "\",\"installations\":"
                + installations + ",\"issuedAt\":\"2026-10-03\",\"expiresAt\":\"" + expiresAt + "\"}";
    }
}
