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

    public boolean verifyCode(String secret, String code) {
        if (secret == null || secret.isBlank() || code == null || code.isBlank()) {
            return false;
        }
        try {
            return TotpUtils.verifyTotp(secret, code.trim());
        } catch (Exception e) {
            return false;
        }
    }
}
