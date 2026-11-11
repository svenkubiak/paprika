package services.storage;

public record S3Response(int status, byte[] body) {
}
