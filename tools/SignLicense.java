import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Issues Paprika license keys in the format checked by utils.Licenses. Only the licensor's private key
 * produces keys Paprika accepts; it is never part of this repository (*.pem is ignored).
 *
 * java tools/SignLicense.java --key paprika-license-private.pem --licensee "ACME GmbH" --installations 3 --expires 2027-10-31 [--id PL-2026-0001]
 *
 * The key is valid through the whole expiry date (UTC). Without --id a random license ID is generated.
 */
public class SignLicense {
    private static final String PREFIX = "PAPRIKA1";

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        String keyFile = required(options, "key");
        String licensee = required(options, "licensee").strip();
        int installations = Integer.parseInt(required(options, "installations"));
        LocalDate expiresAt = LocalDate.parse(required(options, "expires"));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String licenseId = options.getOrDefault("id", defaultId(today));

        if (licensee.isEmpty()) {
            fail("--licensee must not be empty");
        }
        if (installations < 1) {
            fail("--installations must be at least 1");
        }
        if (expiresAt.isBefore(today)) {
            fail("--expires lies in the past: " + expiresAt);
        }

        String json = "{"
                + "\"licenseId\":" + quote(licenseId) + ","
                + "\"licensee\":" + quote(licensee) + ","
                + "\"installations\":" + installations + ","
                + "\"issuedAt\":" + quote(today.toString()) + ","
                + "\"expiresAt\":" + quote(expiresAt.toString())
                + "}";

        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        String signedPart = PREFIX + "." + payload;

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(readPrivateKey(Path.of(keyFile)));
        signer.update(signedPart.getBytes(StandardCharsets.US_ASCII));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());

        System.err.println("License content: " + json);
        System.err.println();
        System.out.println(signedPart + "." + signature);
    }

    private static PrivateKey readPrivateKey(Path file) throws Exception {
        String pem = Files.readString(file);
        if (!pem.contains("-----BEGIN PRIVATE KEY-----")) {
            fail("Expected a PKCS#8 PEM file (-----BEGIN PRIVATE KEY-----) as written by openssl genpkey");
        }
        String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }

    private static String defaultId(LocalDate today) {
        byte[] random = new byte[3];
        new SecureRandom().nextBytes(random);
        return "PL-" + today.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + HexFormat.of().withUpperCase().formatHex(random);
    }

    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                fail("Usage: java SignLicense.java --key <pem> --licensee <name> --installations <n> --expires <yyyy-mm-dd> [--id <license id>]");
            }
            options.put(args[i].substring(2), args[++i]);
        }
        return options;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) {
            fail("--" + name + " is required");
        }
        return value;
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
