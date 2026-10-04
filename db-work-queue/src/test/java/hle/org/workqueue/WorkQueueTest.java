package hle.org.workqueue;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.db2.Db2Container;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real Db2, since the claim relies on SKIP LOCKED DATA. Starting the container takes a few minutes. */
class WorkQueueTest {

    static final Db2Container DB2 = new Db2Container("icr.io/db2_community/db2:12.1.5.0")
            .acceptLicense()
            .withCreateContainerCmdModifier(cmd -> cmd.withPlatform("linux/amd64"))
            .withStartupTimeout(Duration.ofMinutes(15));

    static HikariDataSource pool;
    static JdbcClient db;

    @BeforeAll
    static void startDb2() {
        DB2.start();
        pool = new HikariDataSource();
        pool.setJdbcUrl(DB2.getJdbcUrl());
        pool.setUsername(DB2.getUsername());
        pool.setPassword(DB2.getPassword());
        pool.setMaximumPoolSize(16);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(pool);
        db = JdbcClient.create(pool);
    }

    @AfterAll
    static void stopDb2() {
        pool.close();
        DB2.stop();
    }

    @BeforeEach
    void emptyQueue() {
        db.sql("DELETE FROM WORK_ITEM").update();
    }

    @Test
    void instancesShareTheQueueAndRunEveryJobOnce() {
        IntStream.range(0, 300).forEach(i -> enqueue("op-" + i));
        Map<String, Integer> calls = new ConcurrentHashMap<>();
        List<WorkQueue> instances = IntStream.range(0, 3)
                .mapToObj(i -> queue(4, 60, 5, (op, payload) -> calls.merge(op, 1, Integer::sum)))
                .toList();

        instances.forEach(WorkQueue::start);
        awaitCount("DONE", 300);
        instances.forEach(WorkQueue::stop);

        assertThat(calls).hasSize(300);
        assertThat(calls.values()).containsOnly(1);
    }

    @Test
    void failingJobIsRetriedAfterTheBackoffThenFailed() {
        enqueue("bad");
        List<Long> calls = new CopyOnWriteArrayList<>();
        var settings = new WorkQueue.Settings(1, Duration.ofSeconds(60), 3, Duration.ofMillis(500), Duration.ofMillis(100));
        WorkQueue queue = new WorkQueue(db, settings, (op, payload) -> {
            calls.add(System.nanoTime());
            throw new IllegalStateException("boom");
        });

        queue.start();
        awaitCount("FAILED", 1);
        queue.stop();

        assertThat(calls).hasSize(3);
        assertThat(IntStream.range(1, 3).mapToObj(i -> Duration.ofNanos(calls.get(i) - calls.get(i - 1))))
                .allSatisfy(gap -> assertThat(gap).isGreaterThan(Duration.ofMillis(450)));
        assertThat(row("bad")).containsEntry("ATTEMPTS", 3);
        assertThat((String) row("bad").get("LAST_ERROR")).contains("boom");
    }

    @Test
    void handlerThatOutlivesItsLeaseIsInterruptedAndFreesItsWorker() {
        enqueue("hang");
        enqueue("next");
        WorkQueue queue = queue(1, 1, 1, (op, payload) -> {
            if (op.equals("hang")) {
                new CountDownLatch(1).await(); // returns only when interrupted
            }
        });

        queue.start();
        awaitCount("FAILED", 1);
        awaitCount("DONE", 1);
        queue.stop();

        assertThat((String) row("hang").get("LAST_ERROR")).contains("outlived its lease");
        assertThat(row("next")).containsEntry("STATUS", "DONE");
    }

    @Test
    void expiredLeaseIsReclaimedAndTheStaleWorkerIsFenced() throws Exception {
        enqueue("slow");
        CompletableFuture<Void> releaseStale = new CompletableFuture<>();
        CountDownLatch freshRunning = new CountDownLatch(1);
        CountDownLatch releaseFresh = new CountDownLatch(1);
        // join() ignores the interrupt at lease end, so the stale worker's timeout is recorded only after release:
        // it would set PENDING if it were not fenced.
        WorkQueue stale = queue(1, 1, 5, (op, payload) -> releaseStale.join());
        WorkQueue fresh = queue(1, 60, 5, (op, payload) -> {
            freshRunning.countDown();
            releaseFresh.await();
        });
        stale.start();
        await().until(() -> row("slow").get("STATUS").equals("CLAIMED"));
        fresh.start();
        freshRunning.await(); // reclaimed once the stale worker's 1s lease ran out

        releaseStale.complete(null);
        stale.stop(); // waits for the stale worker's write, made while the fresh claim still holds the row
        assertThat(row("slow")).containsEntry("STATUS", "CLAIMED").containsEntry("ATTEMPTS", 2);

        releaseFresh.countDown();
        awaitCount("DONE", 1);
        fresh.stop();
        assertThat(row("slow").get("LAST_ERROR")).isNull();
    }

    @Test
    void lastAttemptThatExpiredIsFailedWithoutRunningAgain() {
        enqueue("crashed");
        // A worker claimed the last attempt and died: CLAIMED, attempts used up, lease already over.
        db.sql("UPDATE WORK_ITEM SET STATUS = 'CLAIMED', ATTEMPTS = 3 WHERE OPERATION_ID = 'crashed'").update();
        AtomicInteger calls = new AtomicInteger();
        WorkQueue queue = queue(1, 60, 3, (op, payload) -> calls.incrementAndGet());

        queue.start();
        awaitCount("FAILED", 1);
        queue.stop();

        assertThat(calls).hasValue(0);
        assertThat(row("crashed")).containsEntry("LAST_ERROR", "lease expired on the last attempt");
    }

    @Test
    void handlerIsNotStartedWhenClaimingTookTheWholeLease() {
        enqueue("late");
        AtomicInteger calls = new AtomicInteger();
        var settings = new WorkQueue.Settings(1, Duration.ofSeconds(1), 2, Duration.ZERO, Duration.ofMillis(100));
        WorkQueue queue = new WorkQueue(slowDb(Duration.ofMillis(1200), new CountDownLatch(1)), settings,
                (op, payload) -> calls.incrementAndGet());

        queue.start();
        awaitCount("FAILED", 1); // every claim outlasted its lease, until the attempts ran out
        queue.stop();

        assertThat(calls).hasValue(0);
    }

    @Test
    void claimThatReturnsAfterStopIsNotRun() throws Exception {
        enqueue("late");
        CountDownLatch claiming = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var settings = new WorkQueue.Settings(1, Duration.ofSeconds(60), 5, Duration.ZERO, Duration.ofMillis(100));
        WorkQueue queue = new WorkQueue(slowDb(Duration.ofMillis(500), claiming), settings,
                (op, payload) -> calls.incrementAndGet());

        queue.start();
        claiming.await();
        queue.stop(); // returns once the claim in flight comes back

        assertThat(calls).hasValue(0);
        assertThat(row("late")).containsEntry("STATUS", "CLAIMED");
    }

    @Test
    void startingTwiceOrRestartingNeverExceedsWorkers() {
        IntStream.range(0, 20).forEach(i -> enqueue("op-" + i));
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        WorkQueue queue = queue(1, 60, 5, (op, payload) -> {
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            Thread.sleep(20);
            active.decrementAndGet();
        });

        queue.start();
        queue.start();
        awaitCount("DONE", 5);
        queue.stop();
        queue.start();
        awaitCount("DONE", 20);
        queue.stop();

        assertThat(maxActive).hasValue(1);
    }

    static WorkQueue queue(int workers, int leaseSeconds, int maxAttempts, WorkQueue.Handler handler) {
        var settings = new WorkQueue.Settings(workers, Duration.ofSeconds(leaseSeconds), maxAttempts,
                Duration.ZERO, Duration.ofMillis(100));
        return new WorkQueue(db, settings, handler);
    }

    /** Every connection takes {@code delay}, like a slow network or a busy pool. */
    static JdbcClient slowDb(Duration delay, CountDownLatch connecting) {
        return JdbcClient.create(new DelegatingDataSource(pool) {
            @Override
            public Connection getConnection() throws SQLException {
                connecting.countDown();
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    throw new SQLException(e);
                }
                return super.getConnection();
            }
        });
    }

    static void enqueue(String operationId) {
        db.sql("INSERT INTO WORK_ITEM (OPERATION_ID, PAYLOAD) VALUES (?, 'payload')").param(operationId).update();
    }

    static Map<String, Object> row(String operationId) {
        return db.sql("SELECT STATUS, ATTEMPTS, LAST_ERROR FROM WORK_ITEM WHERE OPERATION_ID = ?")
                .param(operationId).query().singleRow();
    }

    static void awaitCount(String status, int expected) {
        await().atMost(Duration.ofSeconds(30)).until(() -> db.sql("SELECT COUNT(*) FROM WORK_ITEM WHERE STATUS = ?")
                .param(status).query(Integer.class).single() >= expected);
    }
}
