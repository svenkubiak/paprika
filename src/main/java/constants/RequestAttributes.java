package constants;

public final class RequestAttributes {
    public static final String REQUEST_START = "paprika.request.start";

    // Shared by the request log entry and any async hook entry of this request.
    public static final String REQUEST_ID = "paprika.request.id";

    public static final String HOOK_FIRED = "paprika.hook.fired";
    public static final String HOOK_BLOCKED = "paprika.hook.blocked";

    public static final String HOOK_INVOCATIONS = "paprika.hook.invocations";

    private RequestAttributes() {
    }
}
