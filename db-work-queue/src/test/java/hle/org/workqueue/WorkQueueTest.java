package hle.org.workqueue;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.db2.Db2Container;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    void failingJobIsRetriedThenFailed() {
        enqueue("bad");
        AtomicInteger calls = new AtomicInteger();
        WorkQueue queue = queue(1, 60, 3, (op, payload) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        });

        queue.start();
        awaitCount("FAILED", 1);
        queue.stop();

        assertThat(calls).hasValue(3);
        assertThat(row("bad")).containsEntry("ATTEMPTS", 3);
        assertThat((String) row("bad").get("LAST_ERROR")).contains("boom");
    }

    @Test
    void expiredLeaseIsReclaimedAndTheStaleWorkerIsFenced() throws Exception {
        enqueue("slow");
        CountDownLatch releaseStale = new CountDownLatch(1);
        CountDownLatch freshRunning = new CountDownLatch(1);
        CountDownLatch releaseFresh = new CountDownLatch(1);
        WorkQueue stale = queue(1, 1, 5, (op, payload) -> {
            releaseStale.await();
            throw new IllegalStateException("late failure"); // would set PENDING if it were not fenced
        });
        WorkQueue fresh = queue(1, 60, 5, (op, payload) -> {
            freshRunning.countDown();
            releaseFresh.await();
        });
        stale.start();
        await().until(() -> row("slow").get("STATUS").equals("CLAIMED"));
        fresh.start();
        freshRunning.await(); // reclaimed once the stale worker's 1s lease ran out

        releaseStale.countDown();
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

    static WorkQueue queue(int workers, int leaseSeconds, int maxAttempts, WorkQueue.Handler handler) {
        var settings = new WorkQueue.Settings(workers, Duration.ofSeconds(leaseSeconds), maxAttempts,
                Duration.ZERO, Duration.ofMillis(100));
        return new WorkQueue(db, settings, handler);
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
                .param(status).query(Integer.class).single() == expected);
    }
}
