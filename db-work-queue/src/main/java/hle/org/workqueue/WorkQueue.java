package hle.org.workqueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * WORK_ITEM as a work queue shared by any number of instances. Each instance runs {@code workers} virtual
 * threads, and each thread loops: claim one row, run the handler, record the outcome.
 * <ul>
 *   <li>Claim: one statement leases the oldest available row, skipping rows other claimers have locked.</li>
 *   <li>Crash or hang: the lease runs out and any worker claims the row again. The lease is also the handler's
 *       timeout: a handler still running when it ends is interrupted.</li>
 *   <li>Fencing: outcomes are written only if ATTEMPTS still equals the value this claim set, so a worker whose
 *       lease ran out cannot overwrite a newer claim.</li>
 * </ul>
 * Delivery is at-least-once: a crash after the handler, or a handler that outlives its lease, runs it again.
 * Handlers must make their effects idempotent on operationId.
 * <p>
 * {@code workers} is a hard concurrency limit. A handler that ignores the interrupt keeps its worker until it returns,
 * and an instance whose workers are all stuck stops claiming: its rows wait for a free worker on another instance.
 */
public class WorkQueue implements SmartLifecycle {

    /** Must bound its downstream calls with timeouts and stop when interrupted. */
    @FunctionalInterface
    public interface Handler {
        void handle(String operationId, String payload) throws Exception;
    }

    /** @param lease how long a claim lasts; a handler still running when it ends is interrupted */
    @ConfigurationProperties("workqueue")
    public record Settings(@DefaultValue("16") int workers,
                           @DefaultValue("60s") Duration lease,
                           @DefaultValue("5") int maxAttempts,
                           @DefaultValue("30s") Duration retryBackoff,
                           @DefaultValue("1s") Duration pollInterval) {
        public Settings {
            if (workers < 1 || maxAttempts < 1 || lease.toMillis() < 1 || retryBackoff.isNegative()
                    || !pollInterval.isPositive()) {
                throw new IllegalArgumentException("workqueue needs workers >= 1, max-attempts >= 1, lease >= 1ms, "
                        + "retry-backoff >= 0, poll-interval > 0");
            }
        }
    }

    record Job(long id, String operationId, String payload, int attempts) {
    }

    // ponytail: one row per claim, so an idle instance runs `workers` claims per poll-interval. Claim in batches
    // if that load matters.
    private static final String CLAIM = """
            SELECT ID, OPERATION_ID, PAYLOAD, ATTEMPTS FROM FINAL TABLE (
              UPDATE (SELECT ID, OPERATION_ID, PAYLOAD, STATUS, ATTEMPTS, AVAILABLE_AT FROM WORK_ITEM
                       WHERE AVAILABLE_AT <= CURRENT TIMESTAMP ORDER BY AVAILABLE_AT FETCH FIRST 1 ROW ONLY)
                 SET STATUS = 'CLAIMED', ATTEMPTS = ATTEMPTS + 1,
                     AVAILABLE_AT = CURRENT TIMESTAMP + CAST(:lease AS DECIMAL(18, 3)) SECONDS)
            SKIP LOCKED DATA
            """;

    private static final String RETRY = """
            UPDATE WORK_ITEM
               SET STATUS = 'PENDING', LAST_ERROR = :error,
                   AVAILABLE_AT = CURRENT TIMESTAMP + CAST(:backoff AS DECIMAL(18, 3)) SECONDS
             WHERE ID = :id AND ATTEMPTS = :attempts AND STATUS = 'CLAIMED'
            """;

    private static final String FINISH = """
            UPDATE WORK_ITEM
               SET STATUS = :status, LAST_ERROR = :error, AVAILABLE_AT = NULL
             WHERE ID = :id AND ATTEMPTS = :attempts AND STATUS = 'CLAIMED'
            """;

    private static final Logger log = LoggerFactory.getLogger(WorkQueue.class);

    private final JdbcClient db;
    private final Settings settings;
    private final Handler handler;
    private volatile boolean running;
    private ExecutorService workers;

    public WorkQueue(JdbcClient db, Settings settings, Handler handler) {
        this.db = db;
        this.settings = settings;
        this.handler = handler;
    }

    /** Does nothing if running. Refuses to start while threads from the previous run are still alive. */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (workers != null && !workers.isTerminated()) {
            throw new IllegalStateException("handlers from the previous run have not stopped yet");
        }
        running = true;
        workers = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < settings.workers(); i++) {
            workers.execute(this::work);
        }
    }

    /** Lets running jobs finish for up to 30s, then interrupts them. Rows left CLAIMED rerun after their lease. */
    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        workers.shutdown();
        try {
            if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void work() {
        while (running) {
            boolean claimed;
            try {
                claimed = processNext();
            } catch (Exception e) { // database unreachable: back off as if the queue were empty
                log.error("work queue database call failed", e);
                claimed = false;
            }
            if (!claimed) {
                try {
                    Thread.sleep(settings.pollInterval());
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** Claims one row and records its outcome. False when no row is available. */
    private boolean processNext() {
        // The lease Db2 gets, counted from before the claim is sent, so it never ends after Db2's.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.lease().toMillis());
        Job job = db.sql(CLAIM).param("lease", seconds(settings.lease())).query(Job.class).optional().orElse(null);
        if (job == null) {
            return false;
        }
        if (job.attempts() > settings.maxAttempts()) { // the last attempt crashed or outlived its lease
            save(job, "FAILED", "lease expired on the last attempt");
            return true;
        }
        // Not run: the row waits out its lease, as after a crash.
        if (!running) {
            return true;
        }
        Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
        if (!remaining.isPositive()) {
            log.warn("job {} attempt {}: claiming took the whole lease; not run", job.id(), job.attempts());
            return true;
        }
        try {
            runHandler(job, remaining);
        } catch (Throwable t) { // any handler failure, Errors included, is a failed attempt, not a dead worker
            log.warn("job {} attempt {} failed", job.id(), job.attempts(), t);
            // 300 chars stay within LAST_ERROR's 1000 bytes in UTF-8
            String error = StringUtils.truncate(String.valueOf(t), 300);
            save(job, job.attempts() < settings.maxAttempts() ? "PENDING" : "FAILED", error);
            return true;
        }
        save(job, "DONE", null);
        return true;
    }

    private void save(Job job, String status, String error) {
        int updated = db.sql(status.equals("PENDING") ? RETRY : FINISH)
                .param("status", status)
                .param("error", error)
                .param("backoff", seconds(settings.retryBackoff()))
                .param("id", job.id())
                .param("attempts", job.attempts())
                .update();
        if (updated == 0) {
            log.warn("job {} attempt {} lost its lease to a newer claim; {} not recorded", job.id(), job.attempts(), status);
        }
    }

    /**
     * Runs the handler on its own thread and interrupts it when the lease ends. Returns only once that thread has
     * stopped, so a handler that ignores the interrupt keeps this worker busy instead of exceeding {@code workers}.
     */
    private void runHandler(Job job, Duration remaining) throws Throwable {
        FutureTask<Void> task = new FutureTask<>(() -> {
            handler.handle(job.operationId(), job.payload());
            return null;
        });
        Thread thread = Thread.ofVirtual().start(task);
        try {
            if (!thread.join(remaining)) {
                log.warn("job {} attempt {} outlived its lease; interrupting it", job.id(), job.attempts());
                thread.interrupt();
                thread.join();
                throw new TimeoutException("handler outlived its lease");
            }
        } catch (InterruptedException e) { // stop() gave up waiting: interrupt the handler too
            thread.interrupt();
            thread.join();
            throw e;
        }
        try {
            task.get();
        } catch (ExecutionException e) {
            throw e.getCause();
        }
    }

    /** Db2 SECONDS durations take decimals, so sub-second settings keep their millisecond precision. */
    private static BigDecimal seconds(Duration duration) {
        return BigDecimal.valueOf(duration.toMillis(), 3);
    }
}
