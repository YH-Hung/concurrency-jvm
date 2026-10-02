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

/** Spec §6 {@code BacklogSampler}: the gauges read its latest successful sample, and its age shows how old that is. */
class BacklogSamplerTest {

    private static final long SECOND = 1_000_000_000L;
    private static final BacklogSample SAMPLE = new BacklogSample(12, 4, 1, 0, ofSeconds(30));

    private final ScriptedRepository repository = new ScriptedRepository();
    private final AtomicLong now = new AtomicLong(Long.MAX_VALUE - 5 * SECOND);
    private final DbActivity db = new DbActivity(now::get);
    private final BacklogSampler sampler = new BacklogSampler(repository, "instance-a", db, now::get);
    private final Logger samplerLog = (Logger) LoggerFactory.getLogger(BacklogSampler.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logged.start();
        samplerLog.addAppender(logged);
    }

    @AfterEach
    void releaseLogs() {
        samplerLog.detachAppender(logged);
    }

    @Test
    void thereIsNoSampleBeforeTheFirst() {
        assertThat(sampler.latest()).isNull();
        assertThat(sampler.errors()).isZero();
    }

    @Test
    void theSampleAgeCountsFromCreationThenFromTheLatestSuccessfulSample() {
        now.addAndGet(5 * SECOND);
        assertThat(sampler.age()).isEqualTo(ofSeconds(5));
        repository.thenSample(SAMPLE).thenSampleThrow(new DataAccessResourceFailureException("Db2 unreachable"));

        sampler.sampleOnce();
        now.addAndGet(3 * SECOND);
        assertThat(sampler.age()).isEqualTo(ofSeconds(3));
        sampler.sampleOnce();   // fails: the age keeps growing
        now.addAndGet(4 * SECOND);

        assertThat(sampler.age()).isEqualTo(ofSeconds(7));
    }

    @Test
    void otherDbSuccessesDoNotRefreshTheSampleAge() {
        now.addAndGet(5 * SECOND);

        db.succeeded();   // a claim, renewal round or sweep that returned

        assertThat(db.lastSuccessAge()).isZero();
        assertThat(sampler.age()).isEqualTo(ofSeconds(5));
    }

    @Test
    void aSampleIsKeptAndIsADbSuccess() {
        now.addAndGet(5 * SECOND);
        repository.thenSample(SAMPLE);

        assertThat(sampler.sampleOnce()).isTrue();

        assertThat(sampler.latest()).isEqualTo(SAMPLE);
        assertThat(db.lastSuccessAge()).isZero();
        assertThat(logged.list).isEmpty();
    }

    @Test
    void aFailedSampleKeepsThePreviousOneAndLogsOnlyClassNames() {
        repository.thenSample(SAMPLE).thenSampleThrow(new DataAccessResourceFailureException("row of order-7:charge"));
        sampler.sampleOnce();
        now.addAndGet(5 * SECOND);

        assertThat(sampler.sampleOnce()).isFalse();

        assertThat(sampler.latest()).isEqualTo(SAMPLE);
        assertThat(sampler.age()).isEqualTo(ofSeconds(5));
        assertThat(sampler.errors()).isEqualTo(1);
        assertThat(db.lastSuccessAge()).isEqualTo(ofSeconds(5));
        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).isEqualTo("Backlog sample by owner instance-a failed: "
                    + "org.springframework.dao.DataAccessResourceFailureException");
        });
    }

    @Test
    void aMissingSampleIsAFailure() {
        repository.thenSample(() -> null);

        assertThat(sampler.sampleOnce()).isFalse();

        assertThat(sampler.latest()).isNull();
        assertThat(sampler.errors()).isEqualTo(1);
    }
}
