package services;

import constants.SettingKeys;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.License;
import utils.Licenses;

import java.security.PublicKey;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

@Singleton
public class LicenseService {
    // Built in rather than configurable, so a license cannot be vouched for by swapping the key in the
    // config. The signing key never leaves the licensor. On rotation a key is added, never replaced, so
    // licenses issued before stay valid.
    private static final List<PublicKey> PUBLIC_KEYS = List.of(
            Licenses.publicKey("MCowBQYDK2VwAyEADn077dUK/sbGl/usaA+JZjlUrxia5PqvYi4p7vLyO/A="));

    private final SettingsService settingsService;

    @Inject
    public LicenseService(SettingsService settingsService) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService must not be null");
    }

    public License current() {
        return check(settingsService.get(SettingKeys.LICENSE_KEY, null));
    }

    public License check(String key) {
        return Licenses.verify(key, PUBLIC_KEYS, LocalDate.now(ZoneOffset.UTC));
    }
}
