package models;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The outcome of checking the stored license key. Display only: no feature depends on it, the rights
 * come from the license agreement, not from the key.
 */
public record License(
        Status status,
        String licenseId,
        String licensee,
        Integer installations,
        LocalDate expiresAt) {

    public enum Status {
        // No key stored, the installation runs under the noncommercial license
        NONE,
        VALID,
        EXPIRED,
        // Unreadable, tampered with or signed by a key this build does not know
        INVALID
    }

    public static License none() {
        return new License(Status.NONE, null, null, null, null);
    }

    public static License invalid() {
        return new License(Status.INVALID, null, null, null, null);
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status.name().toLowerCase(Locale.ROOT));
        if (licenseId != null) {
            payload.put("licenseId", licenseId);
        }
        if (licensee != null) {
            payload.put("licensee", licensee);
        }
        if (installations != null) {
            payload.put("installations", installations);
        }
        if (expiresAt != null) {
            payload.put("expiresAt", expiresAt.toString());
        }
        return payload;
    }
}
