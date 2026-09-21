package hooks;

/**
 * What one hook call cost and how it ended. Deliberately metadata only: neither the envelope nor
 * the hook's response body is kept, because both carry the record's payload - and that is the
 * user's personal data, not diagnostic information.
 *
 * @param name     the configured hook name
 * @param event    the hook event, e.g. {@code beforeCreate}
 * @param target   host (and port) of the hook URL, without path or query
 * @param status   the HTTP status the hook answered with, {@code null} if it never answered
 * @param duration wall clock time of the call in milliseconds
 * @param outcome  one of {@code continued}, {@code blocked}, {@code issuedToken}, {@code failed},
 *                 {@code failedOpen}
 */
public record HookInvocation(
        String name,
        String event,
        String target,
        Integer status,
        long duration,
        String outcome
) {
    public static final String OUTCOME_CONTINUED = "continued";
    public static final String OUTCOME_BLOCKED = "blocked";
    public static final String OUTCOME_ISSUED_TOKEN = "issuedToken";
    public static final String OUTCOME_FAILED = "failed";
    public static final String OUTCOME_FAILED_OPEN = "failedOpen";
}
