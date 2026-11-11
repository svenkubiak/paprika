package services.storage;

import java.io.IOException;
import java.util.Map;

/** One signed call. {@code key} is the object key; {@code null} addresses the bucket itself. */
public interface S3Request {
    S3Response exchange(String method, String key, byte[] body, Map<String, String> query) throws IOException;
}
