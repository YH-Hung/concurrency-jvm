package hle.org.workqueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * WORK_ITEM as a work queue shared by any number of instances. Each instance runs {@code workers} virtual
 * threads, and each thread loops: claim one row, run the handler, record the outcome.
 * <ul>
 *   <li>Claim: one statement leases the oldest available row, skipping rows other claimers have locked.</li>
 *   <li>Crash or hang: the lease runs out and any worker claims the row again.</li>
 *   <li>Fencing: outcomes are written only if ATTEMPTS still equals the value this claim set, so a worker whose
 *       lease ran out cannot overwrite a newer claim.</li>
 * </ul>
 * Delivery is at-least-once: a crash after the handler, or a handler that outlives its lease, runs it again.
 * Handlers must make their effects idempotent on operationId.
 */
public class WorkQueue implements SmartLifecycle {

    @FunctionalInterface
    public interface Handler {
        void handle(String operationId, String payload) throws Exception;
    }

    /** @param lease must exceed the handler's worst-case run time, or another worker runs the row meanwhile */
    @ConfigurationProperties("workqueue")
    public record Settings(@DefaultValue("16") int workers,
                           @DefaultValue("60s") Duration lease,
                           @DefaultValue("5") int maxAttempts,
                           @DefaultValue("30s") Duration retryBackoff,
                           @DefaultValue("1s") Duration pollInterval) {
        public Settings {
            if (workers < 1 || maxAttempts < 1 || lease.toSeconds() < 1) {
                throw new IllegalArgumentException("workqueue needs workers >= 1, max-attempts >= 1, lease >= 1s");
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
                     AVAILABLE_AT = CURRENT TIMESTAMP + CAST(:lease AS INTEGER) SECONDS)
            SKIP LOCKED DATA
            """;

    private static final String RETRY = """
            UPDATE WORK_ITEM
               SET STATUS = 'PENDING', LAST_ERROR = :error,
                   AVAILABLE_AT = CURRENT TIMESTAMP + CAST(:backoff AS INTEGER) SECONDS
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

    @Override
    public void start() {
        running = true;
        workers = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < settings.workers(); i++) {
            workers.execute(this::work);
        }
    }

    /** Lets running jobs finish for up to 30s, then interrupts them. Rows left CLAIMED rerun after their lease. */
    @Override
    public void stop() {
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
        Job job = db.sql(CLAIM).param("lease", settings.lease().toSeconds()).query(Job.class).optional().orElse(null);
        if (job == null) {
            return false;
        }
        if (job.attempts() > settings.maxAttempts()) { // the last attempt crashed or outlived its lease
            save(job, "FAILED", "lease expired on the last attempt");
            return true;
        }
        try {
            handler.handle(job.operationId(), job.payload());
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
                .param("backoff", settings.retryBackoff().toSeconds())
                .param("id", job.id())
                .param("attempts", job.attempts())
                .update();
        if (updated == 0) {
            log.warn("job {} attempt {} lost its lease to a newer claim; {} not recorded", job.id(), job.attempts(), status);
        }
    }
}
