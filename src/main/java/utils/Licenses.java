package utils;

import com.fasterxml.jackson.databind.JsonNode;
import io.mangoo.utils.JsonUtils;
import models.License;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;

/**
 * Checks license keys of the form {@code PAPRIKA1.<base64url(json)>.<base64url(signature)>}. The Ed25519
 * signature covers {@code PAPRIKA1.<base64url(json)>}, so the format version cannot be swapped either.
 */
public final class Licenses {
    public static final String PREFIX = "PAPRIKA1";
    private static final String ALGORITHM = "Ed25519";

    // A real key is a few hundred characters; anything far beyond that is not worth decoding
    private static final int MAX_LENGTH = 4096;

    private Licenses() {
    }

    /** Parses the base64 body of a {@code -----BEGIN PUBLIC KEY-----} block. */
    public static PublicKey publicKey(String base64) {
        try {
            return KeyFactory.getInstance(ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid license public key", e);
        }
    }

    /** Keys get pasted from mails and PDFs, so line breaks and spaces are dropped rather than rejected. */
    public static String normalize(String key) {
        return key == null ? null : StringUtils.deleteWhitespace(key);
    }

    /**
     * @param publicKeys every key this build accepts; trying all of them means nothing from the key is
     *                   parsed before its signature checks out
     * @param today      a license is valid through the whole of its expiry date
     */
    public static License verify(String key, List<PublicKey> publicKeys, LocalDate today) {
        String normalized = normalize(key);
        if (StringUtils.isEmpty(normalized)) {
            return License.none();
        }
        if (normalized.length() > MAX_LENGTH) {
            return License.invalid();
        }

        String[] parts = normalized.split("\\.", -1);
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            return License.invalid();
        }

        try {
            byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
            byte[] signedPart = (PREFIX + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
            if (!signedByAny(signedPart, signature, publicKeys)) {
                return License.invalid();
            }

            JsonNode payload = JsonUtils.getMapper().readTree(Base64.getUrlDecoder().decode(parts[1]));
            String licenseId = StringUtils.trimToNull(payload.path("licenseId").asText(null));
            String licensee = StringUtils.trimToNull(payload.path("licensee").asText(null));
            JsonNode installations = payload.path("installations");
            String expiresAt = payload.path("expiresAt").asText(null);
            if (licenseId == null || licensee == null || !installations.isInt()
                    || installations.intValue() < 1 || expiresAt == null) {
                return License.invalid();
            }

            LocalDate expiryDate = LocalDate.parse(expiresAt);
            License.Status status = today.isAfter(expiryDate) ? License.Status.EXPIRED : License.Status.VALID;
            return new License(status, licenseId, licensee, installations.intValue(), expiryDate);
        } catch (IllegalArgumentException | IOException | GeneralSecurityException | DateTimeParseException e) {
            return License.invalid();
        }
    }

    private static boolean signedByAny(byte[] data, byte[] signature, List<PublicKey> publicKeys)
            throws GeneralSecurityException {
        for (PublicKey publicKey : publicKeys) {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(data);
            if (verifier.verify(signature)) {
                return true;
            }
        }
        return false;
    }
}
