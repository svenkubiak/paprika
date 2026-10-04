package utils;

import models.License;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.PublicKey;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

class LicensesTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);
    private static final KeyPair KEYS = LicenseTestKeys.newKeyPair();
    private static final List<PublicKey> ACCEPTED = List.of(KEYS.getPublic());

    @Test
    void validKeyCarriesItsContent() {
        String key = LicenseTestKeys.sign(LicenseTestKeys.payload("ACME GmbH", 3, "2027-10-31"), KEYS.getPrivate());

        License license = Licenses.verify(key, ACCEPTED, TODAY);

        assertThat(license.status(), is(License.Status.VALID));
        assertThat(license.licenseId(), is("PL-TEST-0001"));
        assertThat(license.licensee(), is("ACME GmbH"));
        assertThat(license.installations(), is(3));
        assertThat(license.expiresAt(), is(LocalDate.of(2027, 10, 31)));
    }

    @Test
    void licenseIsValidThroughItsExpiryDate() {
        String key = LicenseTestKeys.sign(LicenseTestKeys.payload("ACME GmbH", 1, "2026-10-03"), KEYS.getPrivate());

        assertThat(Licenses.verify(key, ACCEPTED, TODAY).status(), is(License.Status.VALID));
        assertThat(Licenses.verify(key, ACCEPTED, TODAY.plusDays(1)).status(), is(License.Status.EXPIRED));
    }

    @Test
    void expiredLicenseKeepsItsContent() {
        String key = LicenseTestKeys.sign(LicenseTestKeys.payload("ACME GmbH", 1, "2026-01-31"), KEYS.getPrivate());

        License license = Licenses.verify(key, ACCEPTED, TODAY);

        assertThat(license.status(), is(License.Status.EXPIRED));
        assertThat(license.licensee(), is("ACME GmbH"));
        assertThat(license.expiresAt(), is(LocalDate.of(2026, 1, 31)));
    }

    @Test
    void noKeyMeansNoLicense() {
        assertThat(Licenses.verify(null, ACCEPTED, TODAY).status(), is(License.Status.NONE));
        assertThat(Licenses.verify("", ACCEPTED, TODAY).status(), is(License.Status.NONE));
        assertThat(Licenses.verify(" \n ", ACCEPTED, TODAY).status(), is(License.Status.NONE));
    }

    @Test
    void lineBreaksFromPastingAreIgnored() {
        String key = LicenseTestKeys.sign(LicenseTestKeys.payload("ACME GmbH", 1, "2027-10-31"), KEYS.getPrivate());
        String wrapped = "  " + key.substring(0, 20) + "\n" + key.substring(20, 60) + "\r\n " + key.substring(60) + "\n";

        assertThat(Licenses.verify(wrapped, ACCEPTED, TODAY).status(), is(License.Status.VALID));
    }

    @Test
    void keySignedByAnUnknownKeyIsInvalid() {
        KeyPair stranger = LicenseTestKeys.newKeyPair();
        String key = LicenseTestKeys.sign(LicenseTestKeys.payload("ACME GmbH", 1, "2027-10-31"), stranger.getPrivate());

        assertThat(Licenses.verify(key, ACCEPTED, TODAY).status(), is(License.Status.INVALID));
    }

    @Test
    void anyOfSeveralAcceptedKeysWorks() {
        KeyPair rotated = LicenseTestKeys.newKeyPair();
        String key = LicenseTestKeys.sign(LicenseTestKeys.payload("ACME GmbH", 1, "2027-10-31"), rotated.getPrivate());

        License license = Licenses.verify(key, List.of(KEYS.getPublic(), rotated.getPublic()), TODAY);

        assertThat(license.status(), is(License.Status.VALID));
    }

    @Test
    void tamperedPayloadIsInvalid() {
        String key = LicenseTestKeys.sign(LicenseTestKeys.payload("ACME GmbH", 1, "2027-10-31"), KEYS.getPrivate());
        String[] parts = key.split("\\.");
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                LicenseTestKeys.payload("ACME GmbH", 50, "2099-12-31").getBytes());

        License license = Licenses.verify(parts[0] + "." + forged + "." + parts[2], ACCEPTED, TODAY);

        assertThat(license.status(), is(License.Status.INVALID));
        assertThat(license.licensee(), is(nullValue()));
    }

    @Test
    void signatureAlsoCoversTheFormatPrefix() {
        String key = LicenseTestKeys.sign(LicenseTestKeys.payload("ACME GmbH", 1, "2027-10-31"), KEYS.getPrivate());

        assertThat(Licenses.verify(key.replaceFirst("^PAPRIKA1", "PAPRIKA2"), ACCEPTED, TODAY).status(),
                is(License.Status.INVALID));
    }

    @Test
    void malformedKeysAreInvalid() {
        String key = LicenseTestKeys.sign(LicenseTestKeys.payload("ACME GmbH", 1, "2027-10-31"), KEYS.getPrivate());
        String[] parts = key.split("\\.");

        for (String malformed : List.of(
                "not-a-license",
                "PAPRIKA1.",
                "PAPRIKA1..",
                parts[0] + "." + parts[1],
                key + ".extra",
                parts[0] + "." + parts[1] + ".!!!",
                parts[0] + "." + parts[1] + ".AAAA",
                "PAPRIKA1." + "A".repeat(5000) + "." + parts[2])) {
            assertThat(malformed, Licenses.verify(malformed, ACCEPTED, TODAY).status(), is(License.Status.INVALID));
        }
    }

    @Test
    void signedButIncompletePayloadIsInvalid() {
        for (String json : List.of(
                "{\"licensee\":\"ACME GmbH\",\"installations\":1,\"expiresAt\":\"2027-10-31\"}",
                "{\"licenseId\":\"PL-1\",\"licensee\":\" \",\"installations\":1,\"expiresAt\":\"2027-10-31\"}",
                "{\"licenseId\":\"PL-1\",\"licensee\":\"ACME GmbH\",\"installations\":0,\"expiresAt\":\"2027-10-31\"}",
                "{\"licenseId\":\"PL-1\",\"licensee\":\"ACME GmbH\",\"installations\":\"3\",\"expiresAt\":\"2027-10-31\"}",
                "{\"licenseId\":\"PL-1\",\"licensee\":\"ACME GmbH\",\"installations\":1.5,\"expiresAt\":\"2027-10-31\"}",
                "{\"licenseId\":\"PL-1\",\"licensee\":\"ACME GmbH\",\"installations\":1}",
                "{\"licenseId\":\"PL-1\",\"licensee\":\"ACME GmbH\",\"installations\":1,\"expiresAt\":\"31.10.2027\"}",
                "[]",
                "not json")) {
            String key = LicenseTestKeys.sign(json, KEYS.getPrivate());
            assertThat(json, Licenses.verify(key, ACCEPTED, TODAY).status(), is(License.Status.INVALID));
        }
    }
}
