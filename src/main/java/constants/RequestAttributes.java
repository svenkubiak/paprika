package constants;

/**
 * Keys of the per-request attributes Paprika passes between filters, services and the request log.
 * They live in one place because the writer (a filter or the hook service) and the reader (the
 * request log) never see each other.
 */
public final class RequestAttributes {
    /** Nanotime taken as early as possible, the basis of the logged total execution time. */
    public static final String REQUEST_START = "paprika.request.start";

    /** Correlation id: the request log entry and any async hook entry of this request share it. */
    public static final String REQUEST_ID = "paprika.request.id";

    public static final String HOOK_FIRED = "paprika.hook.fired";
    public static final String HOOK_BLOCKED = "paprika.hook.blocked";

    /** List of {@code hooks.HookInvocation}, one per blocking hook call of this request. */
    public static final String HOOK_INVOCATIONS = "paprika.hook.invocations";

    private RequestAttributes() {
    }
}
