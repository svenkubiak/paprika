package services;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * The gate bounds memory, and the price it charges for that is refused logins. What is asserted
 * here is that it charges that price only when it has to: logins do not arrive evenly spaced, so
 * a collision that clears within a moment has to be served rather than turned away, while an
 * instance that stays full still has to fail fast instead of queueing.
 */
class PasswordHashGateTest {

    @Test
    void aCollisionThatClearsInsideTheWaitIsServed() throws Exception {
        PasswordHashGate gate = new PasswordHashGate(1);
        CountDownLatch taken = new CountDownLatch(1);

        Thread holder = holder(gate, taken, null);
        assertThat("the fixture has to own the only permit first",
                taken.await(5, TimeUnit.SECONDS), equalTo(true));

        Optional<String> result = gate.withPermit(() -> "hashed");

        assertThat("a permit that comes free inside the wait must be used, not refused",
                result, equalTo(Optional.of("hashed")));
        holder.join(TimeUnit.SECONDS.toMillis(5));
    }

    @Test
    void aPermitThatNeverComesFreeIsRefusedAfterTheWait() throws Exception {
        PasswordHashGate gate = new PasswordHashGate(1);
        CountDownLatch taken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder = holder(gate, taken, release);
        assertThat(taken.await(5, TimeUnit.SECONDS), equalTo(true));

        long start = System.nanoTime();
        Optional<String> result = gate.withPermit(() -> "hashed");
        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat("an instance that stays full has to refuse, not queue",
                result, equalTo(Optional.empty()));
        assertThat("and it has to have waited for a permit before it did",
                waited, greaterThanOrEqualTo(PasswordHashGate.WAIT_MILLIS));

        release.countDown();
        holder.join(TimeUnit.SECONDS.toMillis(5));
    }

    /**
     * A verification holds one core for as long as it holds its memory, so the cap is a core
     * budget as much as a memory budget. The floor of two still wins on a single core machine:
     * one slow login must not be able to block the instance on its own.
     */
    @Test
    void theCapStaysWithinTheCoreCount() {
        assertThat(PasswordHashGate.defaultPermits(),
                lessThanOrEqualTo(Math.max(2, Runtime.getRuntime().availableProcessors())));
    }

    /** Starts a thread that takes a permit and holds it until {@code release} (or briefly). */
    private static Thread holder(PasswordHashGate gate, CountDownLatch taken, CountDownLatch release) {
        Thread holder = new Thread(() -> gate.withPermit(() -> {
            taken.countDown();
            try {
                if (release == null) {
                    Thread.sleep(50);
                } else {
                    release.await(30, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Boolean.TRUE;
        }));
        holder.setDaemon(true);
        holder.start();
        return holder;
    }
}
