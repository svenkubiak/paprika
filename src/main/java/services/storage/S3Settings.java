package services.storage;

import java.util.Objects;
import java.util.regex.Pattern;

/** Checked before any network call, so a missing credential fails without contacting the bucket. */
public record S3Settings(
        String bucket,
        String region,
        String configuredEndpoint,
        boolean pathStyle,
        String accessKey,
        String secret) {

    private static final Pattern REGION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]*");
    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");

    public S3Settings {
        Objects.requireNonNull(bucket, "bucket must not be null");
        Objects.requireNonNull(region, "region must not be null");
        Objects.requireNonNull(accessKey, "accessKey must not be null");
        Objects.requireNonNull(secret, "secret must not be null");
    }

    public void validate() {
        if (!BUCKET.matcher(bucket).matches() || bucket.contains("..")) {
            throw new IllegalStateException(
                    "paprika.storage_bucket must be an S3 bucket name, but is \"" + bucket
                            + "\". Set PAPRIKA_STORAGE_BUCKET.");
        }
        if (!REGION.matcher(region).matches()) {
            throw new IllegalStateException(
                    "paprika.storage_region must be an S3 region, but is \"" + region
                            + "\". Set PAPRIKA_STORAGE_REGION.");
        }
        if (accessKey.isBlank()) {
            throw new IllegalStateException(
                    "paprika.storage_access_key is empty. Set the secret file paprika.storage_access_key.");
        }
        if (secret.isBlank()) {
            throw new IllegalStateException(
                    "paprika.storage_secret_key is empty. Set the secret file paprika.storage_secret_key.");
        }
        try {
            endpoint();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "paprika.storage_endpoint is not usable (" + e.getMessage()
                            + "). Set PAPRIKA_STORAGE_ENDPOINT or leave it empty for Amazon S3.", e);
        }
    }

    public S3Endpoint endpoint() {
        return S3Endpoint.of(bucket, region, configuredEndpoint, pathStyle);
    }
}
