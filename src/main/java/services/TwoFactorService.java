package services;

import io.mangoo.utils.TotpUtils;
import jakarta.inject.Singleton;

@Singleton
public class TwoFactorService {
    private static final String ISSUER = "Paprika";

    public String generateSecret() {
        return TotpUtils.createSecret();
    }

    public String buildUri(String username, String secret) {
        return TotpUtils.getOtpAuthURL(username, ISSUER, secret);
    }

    /**
     * Accepts each code once per account, on every instance: mangoo keeps the used time step in
     * MongoDB, so a code read over someone's shoulder cannot be replayed within its 30 seconds.
     */
    public boolean verifyCode(String userId, String secret, String code) {
        if (userId == null || userId.isBlank() || secret == null || secret.isBlank() || code == null || code.isBlank()) {
            return false;
        }
        try {
            return TotpUtils.verifyTotp(userId, secret, code.trim());
        } catch (Exception e) {
            return false;
        }
    }
}
