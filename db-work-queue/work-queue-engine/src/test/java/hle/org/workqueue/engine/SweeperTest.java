package hle.org.workqueue.engine;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.concurrent.atomic.AtomicLong;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec §6 {@code Sweeper}: one pass sweeps batches until one comes back less than full. */
class SweeperTest {

    private static final long SECOND = 1_000_000_000L;
    private static final String OWNER = "instance-a";
    private static final int BATCH_SIZE = 100;

    private final ScriptedRepository repository = new ScriptedRepository();
    private final AtomicLong now = new AtomicLong(Long.MAX_VALUE - 5 * SECOND);
    private final DbActivity db = new DbActivity(now::get);
    private final Sweeper sweeper = new Sweeper(repository, OWNER, BATCH_SIZE, db);
    private final Logger sweeperLog = (Logger) LoggerFactory.getLogger(Sweeper.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logged.start();
        sweeperLog.addAppender(logged);
    }

    @AfterEach
    void releaseLogsAndInterruptStatus() {
        sweeperLog.detachAppender(logged);
        Thread.interrupted();
    }

    @Test
    void aPassSweepsFullBatchesUntilOneComesBackShort() {
        repository.thenSweep(100, 100, 7);

        assertThat(sweeper.sweepOnce()).isEqualTo(207);

        assertThat(repository.sweepSizes()).containsExactly(100, 100, 100);
        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo(
                    "Sweeper of owner instance-a marked 207 expired claims with exhausted attempts FAILED");
        });
    }

    @Test
    void aPassWithNothingToSweepMakesOneSweepAndLogsNothing() {
        assertThat(sweeper.sweepOnce()).isZero();

        assertThat(repository.sweepSizes()).containsExactly(100);
        assertThat(logged.list).isEmpty();
    }

    @Test
    void aFailedSweepEndsThePassLogsOnlyClassNamesAndKeepsTheEarlierBatches() {
        repository.thenSweep(100).thenSweepThrow(new DataAccessResourceFailureException("row of order-7:charge"));

        assertThat(sweeper.sweepOnce()).isEqualTo(100);

        assertThat(repository.sweepSizes()).containsExactly(100, 100);
        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "Sweep by owner instance-a failed after 100 rows: "
                        + "org.springframework.dao.DataAccessResourceFailureException",
                "Sweeper of owner instance-a marked 100 expired claims with exhausted attempts FAILED");
        assertThat(logged.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    @Test
    void anInterruptedPassStopsAfterItsCurrentBatch() {
        repository.thenSweep(100, 100);
        Thread.currentThread().interrupt();

        assertThat(sweeper.sweepOnce()).isEqualTo(100);

        assertThat(repository.sweepSizes()).containsExactly(100);
    }

    @Test
    void everySweepThatReturnsIsADbSuccess() {
        now.addAndGet(5 * SECOND);
        repository.thenSweep(() -> {
            now.addAndGet(SECOND);   // the sweep takes a second
            return 0;
        });

        sweeper.sweepOnce();

        assertThat(db.lastSuccessAge()).isZero();
        now.addAndGet(3 * SECOND);
        repository.thenSweepThrow(new DataAccessResourceFailureException("Db2 unreachable"));
        sweeper.sweepOnce();
        assertThat(db.lastSuccessAge()).isEqualTo(ofSeconds(3));
    }

    @Test
    void rejectsABatchSizeBelowOne() {
        assertThatThrownBy(() -> new Sweeper(repository, OWNER, 0, db))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batchSize");
    }
}
