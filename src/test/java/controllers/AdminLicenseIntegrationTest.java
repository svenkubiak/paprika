package controllers;

import constants.SettingKeys;
import dtos.UpdateAdminSettingsDto;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.License;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import results.AdminSettingsResult;
import services.*;
import utils.AdminTestUtils;
import utils.LicenseTestKeys;
import utils.Licenses;

import java.security.KeyPair;
import java.security.PublicKey;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * The built-in public key belongs to the licensor, so no test can sign a key this build accepts. The HTTP
 * tests cover what works without one; storing a valid key runs against a service that trusts a test key.
 */
@ExtendWith({TestRunner.class})
class AdminLicenseIntegrationTest {
    private static final KeyPair TEST_KEYS = LicenseTestKeys.newKeyPair();

    @AfterEach
    void removeLicense() {
        Application.getInstance(SettingsService.class).set(SettingKeys.LICENSE_KEY, "");
    }

    @Test
    void withoutAKeyTheInstallationIsNoncommercial() {
        TestResponse response = getSettings();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), containsString("\"license\":{\"status\":\"none\"}"));
    }

    @Test
    void aKeyThatDoesNotVerifyIsRejectedAndNotStored() {
        String foreignKey = LicenseTestKeys.sign(
                LicenseTestKeys.payload("ACME GmbH", 1, "2099-12-31"), TEST_KEYS.getPrivate());

        TestResponse response = patchSettings("{\"licenseKey\":\"" + foreignKey + "\"}");

        assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        assertThat(response.getContent(), containsString("License key is invalid"));
        assertThat(Application.getInstance(SettingsService.class).get(SettingKeys.LICENSE_KEY, ""), is(""));
    }

    /** A key can stop verifying after it was stored, e.g. once its signing key is retired. */
    @Test
    void aStoredKeyIsReportedButNeverSentBack() {
        String storedKey = LicenseTestKeys.sign(
                LicenseTestKeys.payload("ACME GmbH", 1, "2099-12-31"), TEST_KEYS.getPrivate());
        Application.getInstance(SettingsService.class).set(SettingKeys.LICENSE_KEY, storedKey);

        TestResponse response = getSettings();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), containsString("\"license\":{\"status\":\"invalid\"}"));
        assertThat(response.getContent(), not(containsString(storedKey.split("\\.")[1])));
        assertThat(response.getContent(), not(containsString(SettingKeys.LICENSE_KEY)));
    }

    @Test
    void anEmptyKeyRemovesTheLicense() {
        Application.getInstance(SettingsService.class).set(SettingKeys.LICENSE_KEY, "PAPRIKA1.stored.key");

        TestResponse response = patchSettings("{\"licenseKey\":\"\"}");

        assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(response.getContent(), containsString("\"license\":{\"status\":\"none\"}"));
    }

    @Test
    void aValidKeyIsStoredWithoutTheLineBreaksItWasPastedWith() {
        AdminSettingsService service = serviceTrusting(TEST_KEYS.getPublic());
        String key = LicenseTestKeys.sign(
                LicenseTestKeys.payload("ACME GmbH", 2, "2099-12-31"), TEST_KEYS.getPrivate());

        AdminSettingsResult result = service.update(licenseKey(key.substring(0, 30) + "\n" + key.substring(30)));

        assertThat(result.status(), is(AdminSettingsResult.Status.OK));
        assertThat(Application.getInstance(SettingsService.class).get(SettingKeys.LICENSE_KEY, null), is(key));
        assertThat(result.body().get("license"), is(Map.of(
                "status", "valid",
                "licenseId", "PL-TEST-0001",
                "licensee", "ACME GmbH",
                "installations", 2,
                "expiresAt", "2099-12-31")));
    }

    @Test
    void anExpiredKeyIsRejectedWithItsExpiryDate() {
        AdminSettingsService service = serviceTrusting(TEST_KEYS.getPublic());
        String key = LicenseTestKeys.sign(
                LicenseTestKeys.payload("ACME GmbH", 1, "2020-01-31"), TEST_KEYS.getPrivate());

        AdminSettingsResult result = service.update(licenseKey(key));

        assertThat(result.status(), is(AdminSettingsResult.Status.BAD_REQUEST));
        assertThat(result.errorMessage(), is("License expired on 2020-01-31"));
        assertThat(Application.getInstance(SettingsService.class).get(SettingKeys.LICENSE_KEY, ""), is(""));
    }

    private static AdminSettingsService serviceTrusting(PublicKey publicKey) {
        SettingsService settings = Application.getInstance(SettingsService.class);
        LicenseService licenseService = new LicenseService(settings) {
            @Override
            public License check(String key) {
                return Licenses.verify(key, List.of(publicKey), LocalDate.now());
            }
        };
        return new AdminSettingsService(
                settings,
                Application.getInstance(RequestLogService.class),
                Application.getInstance(TenantService.class),
                licenseService);
    }

    private static UpdateAdminSettingsDto licenseKey(String key) {
        return new UpdateAdminSettingsDto(null, null, null, null, null, key);
    }

    private static TestResponse getSettings() {
        return AdminTestUtils.getWithAdminCookies("/api/admin/settings", AdminTestUtils.loginAsAdminWithDefaultTenant());
    }

    private static TestResponse patchSettings(String body) {
        return AdminTestUtils.patchWithAdminCookies(
                "/api/admin/settings", AdminTestUtils.loginAsAdminWithDefaultTenant(), body, "application/json");
    }
}
