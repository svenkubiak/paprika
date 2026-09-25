package services;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Caps how many Argon2id verifications this instance runs at the same time.
 * <p>
 * Argon2's memory parameter is memory that is really allocated: with mangoo's parameters
 * (m=80000 KiB, t=6, p=2) one verification holds about 91 MiB of heap for roughly a quarter of a
 * second. The endpoints that trigger one are unauthenticated by design, and the login path runs
 * the hash even for a username that does not exist, so that the response time does not give
 * account existence away. That makes a login request a ~80 byte input with a ~91 MiB cost.
 * <p>
 * A rate limiter in front of the application does not bound that: it counts requests, not what
 * they cost, and the burst it is configured with is exactly the number of expensive computations
 * that arrive at once. The documented nginx example allows {@code burst=5} per address, so the
 * peak has to be bounded here, where the cost is known.
 * <p>
 * Requests over the limit are refused, not queued: queueing turns a memory problem into a latency
 * problem and holds the connections open while it does so. A caller that is turned away gets 429
 * and can try again.
 */
@Singleton
public class PasswordHashGate {
    private static final Logger LOG = LogManager.getLogger(PasswordHashGate.class);

    /** Measured peak heap held by one in-flight verification, rounded up. */
    public static final long BYTES_PER_HASH = 96L * 1024 * 1024;

    /**
     * The share of the heap that unauthenticated password hashing may occupy at its peak. The
     * rest belongs to the data plane, the realtime registry and the admin UI, which all run in
     * this same process and must stay serviceable while someone is hammering the login.
     */
    private static final double HEAP_SHARE = 0.25;

    private static final int MIN_PERMITS = 2;
    private static final int MAX_PERMITS = 8;

    private final Semaphore semaphore;
    private final int permits;

    @Inject
    public PasswordHashGate() {
        this(defaultPermits());
        LOG.info("Password hashing is capped at {} concurrent verifications (~{} MiB peak) for a heap of {} MiB",
                permits, permits * (BYTES_PER_HASH >> 20), Runtime.getRuntime().maxMemory() >> 20);
    }

    PasswordHashGate(int permits) {
        if (permits < 1) {
            throw new IllegalArgumentException("permits must be at least 1");
        }
        this.permits = permits;
        // Fair, so a steady stream of attackers cannot starve a legitimate login indefinitely
        this.semaphore = new Semaphore(permits, true);
    }

    /**
     * Derived from the heap rather than from the core count: what a verification is scarce in is
     * memory, and the heap is what an instance gets configured with. A 1 GiB heap admits two, a
     * 4 GiB heap the maximum of eight - beyond that the proxy's rate limit is the sensible place
     * to shape traffic, and more parallelism stops buying throughput anyway.
     */
    static int defaultPermits() {
        long budget = (long) (Runtime.getRuntime().maxMemory() * HEAP_SHARE);
        long derived = budget / BYTES_PER_HASH;
        return (int) Math.clamp(derived, MIN_PERMITS, MAX_PERMITS);
    }

    /**
     * Runs {@code work} while holding a permit, or returns empty without running it when the
     * instance is already at its limit.
     *
     * @param work the hashing to run; must not return {@code null}, so that an empty result
     *             unambiguously means "refused" rather than "ran and produced nothing"
     */
    public <T> Optional<T> withPermit(Supplier<T> work) {
        Objects.requireNonNull(work, "work must not be null");

        if (!semaphore.tryAcquire()) {
            LOG.warn("Refused a password verification: all {} hashing permits are in use", permits);
            return Optional.empty();
        }

        try {
            return Optional.of(Objects.requireNonNull(work.get(), "work must not return null"));
        } finally {
            semaphore.release();
        }
    }

    public int permits() {
        return permits;
    }
}
