package hooks;

import com.fasterxml.jackson.databind.JsonNode;

public record HookExecutionResult(
        boolean continueOperation,
        JsonNode body,
        Integer errorStatus,
        String errorMessage,
        String errorBody
) {
    public static HookExecutionResult proceed(JsonNode body) {
        return new HookExecutionResult(true, body, null, null, null);
    }

    public static HookExecutionResult abort(int status, String message) {
        return new HookExecutionResult(false, null, status, message, null);
    }

    public static HookExecutionResult abortWithBody(int status, String errorBody) {
        return new HookExecutionResult(false, null, status, null, errorBody);
    }

    public static HookExecutionResult proceedUnchanged() {
        return new HookExecutionResult(true, null, null, null, null);
    }
}
