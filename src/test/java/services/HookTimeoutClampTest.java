package services;

import models.HookDefinition;
import models.HookEvent;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * A blocking hook holds the request thread for its whole timeout, and records can bypass validate()
 * (direct write, restored backup), so the value reaching the HTTP client is capped too.
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
