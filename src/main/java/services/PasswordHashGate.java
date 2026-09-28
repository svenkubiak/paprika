package services;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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
 * A caller that finds every permit taken waits briefly for one and is refused when none comes
 * free in time. The wait is there because logins do not arrive evenly spaced: well below the
 * nominal capacity a few that happen to overlap would otherwise be turned away although the
 * instance is idle again a moment later. It is bounded so that a real overload still fails fast -
 * an unbounded queue would trade the memory problem for a latency problem and hold the
 * connections open while it did so. The wait itself costs nothing worth bounding: a waiter holds
 * a connection, not 91 MiB. A caller that is turned away gets 429 and can try again.
 */
@Singleton
public class PasswordHashGate {
    private static final Logger LOG = LogManager.getLogger(PasswordHashGate.class);

    /** Measured peak heap held by one in-flight verification, rounded up. */
    public static final long BYTES_PER_HASH = 96L * 1024 * 1024;

    /**
     * How long a caller waits for a permit before it is refused. Long enough to cover a
     * verification that is already most of the way through its quarter second, short enough that
     * a genuinely full instance answers 429 well inside any sensible client timeout.
     */
    static final long WAIT_MILLIS = 200;

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
        LOG.info("Password hashing is capped at {} concurrent verifications (~{} MiB peak) for a heap of {} MiB on {} cores",
                permits, permits * (BYTES_PER_HASH >> 20), Runtime.getRuntime().maxMemory() >> 20,
                Runtime.getRuntime().availableProcessors());
    }

    PasswordHashGate(int permits) {
        if (permits < 1) {
            throw new IllegalArgumentException("permits must be at least 1");
        }
        this.permits = permits;
        // Fair, so a steady stream of arrivals cannot starve a waiter indefinitely. The flag only
        // means anything because the acquire is timed: the untimed tryAcquire barges past the
        // queue no matter what this is set to.
        this.semaphore = new Semaphore(permits, true);
    }

    /**
     * Derived from the heap and the core count together, because a verification is scarce in
     * both. It allocates ~91 MiB, and it occupies one core for as long as it holds it -
     * BouncyCastle walks Argon2's lanes in a loop, so {@code p=2} is still a single thread. A
     * 1 GiB heap admits two, a 3 GiB heap the maximum of eight, and a container with fewer cores
     * than that admits one per core: permits past the core count buy no throughput, they only
     * stretch every verification in flight and the login latency with them.
     */
    static int defaultPermits() {
        long budget = (long) (Runtime.getRuntime().maxMemory() * HEAP_SHARE);
        long fromHeap = budget / BYTES_PER_HASH;
        long fromCores = Runtime.getRuntime().availableProcessors();
        return (int) Math.clamp(Math.min(fromHeap, fromCores), MIN_PERMITS, MAX_PERMITS);
    }

    /**
     * Runs {@code work} while holding a permit, waiting a bounded moment for one, or returns
     * empty without running it when none comes free in that time.
     *
     * @param work the hashing to run; must not return {@code null}, so that an empty result
     *             unambiguously means "refused" rather than "ran and produced nothing"
     */
    public <T> Optional<T> withPermit(Supplier<T> work) {
        Objects.requireNonNull(work, "work must not be null");

        try {
            if (!semaphore.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                LOG.warn("Refused a password verification: all {} hashing permits stayed in use for {} ms",
                        permits, WAIT_MILLIS);
                return Optional.empty();
            }
        } catch (InterruptedException e) {
            // Shutdown, or a client that went away: answer like a refusal rather than hash anyway
            Thread.currentThread().interrupt();
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
