package hooks;

import com.fasterxml.jackson.databind.JsonNode;

public record HookExecutionResult(
        boolean continueOperation,
        boolean hooksRan,
        JsonNode body,
        Integer errorStatus,
        String errorMessage,
        String errorBody,
        String issueTokenForUserId
) {
    public static HookExecutionResult proceed(JsonNode body) {
        return new HookExecutionResult(true, true, body, null, null, null, null);
    }

    public static HookExecutionResult abort(int status, String message) {
        return new HookExecutionResult(false, true, null, status, message, null, null);
    }

    public static HookExecutionResult abortWithBody(int status, String errorBody) {
        return new HookExecutionResult(false, true, null, status, null, errorBody, null);
    }

    public static HookExecutionResult issueTokenFor(String userId) {
        return new HookExecutionResult(false, true, null, null, null, null, userId);
    }

    public static HookExecutionResult proceedUnchanged() {
        return new HookExecutionResult(true, false, null, null, null, null, null);
    }

    public static HookExecutionResult proceedUnchangedWithHooks() {
        return new HookExecutionResult(true, true, null, null, null, null, null);
    }
}
