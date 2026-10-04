package utils;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class AuthTokensTest {

    @Test
    void expiryIsWrittenInTheFixedWidthFormat() {
        String expiresAt = AuthTokens.expiresAt();

        assertThat(expiresAt, expiresAt.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"), is(true));
        assertThat(AuthTokens.isActive(expiresAt), is(true));
    }

    /** Tokens issued before the change carry Instant.toString() expiries and must keep working. */
    @Test
    void legacyExpiryFormatsAreStillRead() {
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
        Instant past = Instant.now().minus(1, ChronoUnit.MINUTES);

        assertThat(AuthTokens.isActive(future.truncatedTo(ChronoUnit.SECONDS).toString()), is(true));
        assertThat(AuthTokens.isActive(future.toString()), is(true));
        assertThat(AuthTokens.isActive(past.toString()), is(false));
        assertThat(AuthTokens.isActive(Timestamps.format(past)), is(false));
        assertThat(AuthTokens.isActive("garbage"), is(false));
    }
}
