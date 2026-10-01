package hooks;

// Metadata only: envelope and response body carry the record's personal data, not diagnostics.
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
