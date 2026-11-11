package services.storage;

import java.util.Arrays;
import java.util.Objects;

public record S3Response(int status, byte[] body) {
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof S3Response other)) {
            return false;
        }
        return status == other.status && Arrays.equals(body, other.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, Arrays.hashCode(body));
    }

    @Override
    public String toString() {
        return "S3Response[status=" + status + ", body="
                + (body == null ? "null" : body.length + " bytes") + "]";
    }
}
