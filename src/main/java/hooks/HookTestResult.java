package hooks;

public record HookTestResult(
        String deliveryId,
        String requestPayload,
        String signature,
        int statusCode,
        String responseBody,
        long latencyMs,
        String error
) {
}
