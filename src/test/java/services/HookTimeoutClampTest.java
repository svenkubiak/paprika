package services;

import models.HookDefinition;
import models.HookEvent;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * The 30s cap is checked when a hook is saved, but a blocking hook holds the request thread for
 * as long as its timeout allows - so the value that actually reaches the HTTP client has to be
 * capped as well. A record can reach the database without passing validate(): a direct write, a
 * restored backup, a migration.
 */
class HookTimeoutClampTest {

    @Test
    void anExcessiveTimeoutIsCappedAtDispatch() {
        assertThat(HookService.effectiveTimeoutMs(hookWithTimeout(Integer.MAX_VALUE, HookEvent.beforeCreate)),
                equalTo(30_000));
    }

    @Test
    void aTimeoutWithinTheCapIsUsedAsItIs() {
        assertThat(HookService.effectiveTimeoutMs(hookWithTimeout(1_500, HookEvent.beforeCreate)),
                equalTo(1_500));
    }

    /** The defaults are below the cap and must not be rewritten by the clamp. */
    @Test
    void theDefaultsAreUnaffected() {
        assertThat(HookService.effectiveTimeoutMs(hookWithTimeout(null, HookEvent.beforeCreate)),
                equalTo(5_000));
        assertThat(HookService.effectiveTimeoutMs(hookWithTimeout(null, HookEvent.afterCreate)),
                equalTo(30_000));
    }

    private static HookDefinition hookWithTimeout(Integer timeoutMs, HookEvent event) {
        return new HookDefinition(
                "test",
                "Timeout test hook",
                null,
                "hooks_timeout_test",
                event,
                "https://example.com/hook",
                "POST",
                timeoutMs,
                "test-signing-secret",
                null,
                true,
                50,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
