package hle.org.workqueue.engine;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import hle.org.workqueue.engine.ScriptedRepository.Write;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static hle.org.workqueue.engine.ScriptedRepository.Operation.COMPLETE;
import static hle.org.workqueue.engine.ScriptedRepository.Operation.RETRY_OR_FAIL;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItemProcessorTest {

    private static final String OWNER = "instance-a";
    private static final String NAMESPACE = "it";
    private static final ClaimedItem ITEM = new ClaimedItem(7, "order-7:charge", "payload-7", 3);
    private static final ClaimKey CLAIM = new ClaimKey(7, 3);
    /** The IT column: external-call-timeout 3s, completion-retries 2, completion-retry-delay 100ms. */
    private static final ItemProcessor.Settings SETTINGS = ItemProcessor.Settings.from(ItConfig.properties());

    private final ScriptedRepository repository = new ScriptedRepository();
    private final List<Call> calls = new ArrayList<>();
    private final List<Duration> sleeps = new ArrayList<>();
    private final Logger processorLog = (Logger) LoggerFactory.getLogger(ItemProcessor.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    private record Call(IdempotencyKey key, long claimToken, String payload, Duration timeout) {
    }

    @BeforeEach
    void captureLogs() {
        logged.start();
        processorLog.addAppender(logged);
    }

    @AfterEach
    void releaseLogsAndInterruptStatus() {
        processorLog.detachAppender(logged);
        Thread.interrupted();   // tests of interruption leave the test thread interrupted
    }

    @Test
    void aSuccessfulCallCompletesTheRowWithItsResult() {
        repository.thenReturn(PersistResult.DONE);

        assertThat(process(returning("receipt-7"))).isEqualTo(Outcome.COMPLETED);

        assertThat(calls).containsExactly(
                new Call(new IdempotencyKey("it", "order-7:charge"), 3, "payload-7", ofSeconds(3)));
        assertThat(repository.writes()).containsExactly(new Write(COMPLETE, OWNER, CLAIM, "receipt-7"));
    }

    @Test
    void aCancelledHandleIsNeitherCalledNorPersisted() {
        assertThat(processor(returning("receipt-7")).process(ITEM, () -> true)).isEqualTo(Outcome.CANCELLED);

        assertThat(calls).isEmpty();
        assertThat(repository.writes()).isEmpty();
    }

    @Test
    void aFailedCallSchedulesARetry() {
        repository.thenReturn(PersistResult.RETRY_SCHEDULED);

        assertThat(process(throwing(new IllegalStateException("downstream said no")))).isEqualTo(Outcome.RETRY_SCHEDULED);

        assertThat(calls).hasSize(1);
        assertThat(repository.writes()).containsExactly(
                new Write(RETRY_OR_FAIL, OWNER, CLAIM, "java.lang.IllegalStateException: downstream said no"));
    }

    @Test
    void aFailedCallOnTheLastAttemptFailsTheRow() {
        repository.thenReturn(PersistResult.FAILED);

        assertThat(process(throwing(new IllegalStateException("downstream said no")))).isEqualTo(Outcome.FAILED);
    }

    @Test
    void aTimedOutCallIsAFailedAttempt() {
        repository.thenReturn(PersistResult.RETRY_SCHEDULED);

        assertThat(process(throwing(new TimeoutException("3s passed")))).isEqualTo(Outcome.RETRY_SCHEDULED);

        assertThat(repository.writes()).containsExactly(
                new Write(RETRY_OR_FAIL, OWNER, CLAIM, "java.util.concurrent.TimeoutException: 3s passed"));
    }

    @Test
    void aCallThatReturnsNoResultIsAFailedAttempt() {
        repository.thenReturn(PersistResult.RETRY_SCHEDULED);

        assertThat(process((key, token, payload, timeout) -> null)).isEqualTo(Outcome.RETRY_SCHEDULED);

        assertThat(repository.writes()).containsExactly(
                new Write(RETRY_OR_FAIL, OWNER, CLAIM, ItemProcessor.NO_RESULT_ERROR));
    }

    @Test
    void aRowWithAnInvalidOperationIdFailsItsAttemptWithoutACall() {
        repository.thenReturn(PersistResult.RETRY_SCHEDULED);
        ClaimedItem invalid = new ClaimedItem(8, "has space", "payload-8", 1);

        assertThat(processor(returning("receipt-8")).process(invalid, () -> false)).isEqualTo(Outcome.RETRY_SCHEDULED);

        assertThat(calls).isEmpty();
        assertThat(repository.writes()).containsExactly(
                new Write(RETRY_OR_FAIL, OWNER, new ClaimKey(8, 1), ItemProcessor.INVALID_OPERATION_ID_ERROR));
    }

    @Test
    void aFencedCompletionIsFenced() {
        repository.thenReturn(PersistResult.FENCED);

        assertThat(process(returning("receipt-7"))).isEqualTo(Outcome.FENCED);
    }

    @Test
    void aFencedFailureIsFenced() {
        repository.thenReturn(PersistResult.FENCED);

        assertThat(process(throwing(new IllegalStateException("downstream said no")))).isEqualTo(Outcome.FENCED);
    }

    @Test
    void aCompletionWhoseAcknowledgementWasLostIsCompletedNotFenced() {
        // The first complete commits but its acknowledgement is lost; the retry updates 0 rows, and its read-back
        // finds this owner's token already DONE.
        repository.thenThrow(new DataAccessResourceFailureException("commit acknowledgement lost"))
                .thenReturn(PersistResult.DONE);

        assertThat(process(returning("receipt-7"))).isEqualTo(Outcome.COMPLETED);

        assertThat(calls).hasSize(1);
        assertThat(repository.writes()).extracting(Write::operation).containsExactly(COMPLETE, COMPLETE);
        assertThat(sleeps).containsExactly(ofMillis(100));
    }

    @Test
    void anOutcomeThatCannotBePersistedIsAbandonedAfterTheRetries() {
        DataAccessResourceFailureException unreachable = new DataAccessResourceFailureException("Db2 unreachable");
        repository.thenThrow(unreachable).thenThrow(unreachable).thenThrow(unreachable);

        assertThat(process(returning("receipt-7"))).isEqualTo(Outcome.ABANDONED);

        assertThat(calls).hasSize(1);
        assertThat(repository.writes()).hasSize(3);
        assertThat(sleeps).containsExactly(ofMillis(100), ofMillis(100));
    }

    @Test
    void anUnexpectedRepositoryExceptionIsAbandonedNotThrown() {
        IllegalStateException bug = new IllegalStateException("not a persisted status: CLAIMED");
        repository.thenThrow(bug).thenThrow(bug).thenThrow(bug);

        assertThat(process(throwing(new IllegalStateException("downstream said no")))).isEqualTo(Outcome.ABANDONED);
    }

    @Test
    void anAbandonedOutcomeLogsClassNamesAndSqlCodesButNoMessages() {
        // A persist failure's messages can carry the result, payload or operation id, down its cause chain.
        RuntimeException leaky = new DataAccessResourceFailureException("could not store receipt-7",
                new SQLTransientConnectionException("payload-7 of order-7:charge", "08001", -4499));
        repository.thenThrow(leaky).thenThrow(leaky).thenThrow(leaky);

        assertThat(process(returning("receipt-7"))).isEqualTo(Outcome.ABANDONED);

        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).as("the raw throwable is not logged").isNull();
            assertThat(event.getFormattedMessage())
                    .isEqualTo("Abandoned row 7 token 3 of owner instance-a: its outcome could not be persisted: "
                            + "org.springframework.dao.DataAccessResourceFailureException, caused by "
                            + "java.sql.SQLTransientConnectionException (SQLState 08001, error code -4499)")
                    .doesNotContain("receipt-7", "payload-7", "order-7");
        });
    }

    @Test
    void anAbandonedOutcomeWhoseFailureCannotBeReadIsStillReturned() {
        RuntimeException unreadable = new IllegalStateException() {
            @Override
            public String getMessage() {
                throw new IllegalStateException("getMessage is broken");
            }

            @Override
            public synchronized Throwable getCause() {
                throw new IllegalStateException("getCause is broken");
            }
        };
        repository.thenThrow(unreadable).thenThrow(unreadable).thenThrow(unreadable);

        assertThat(process(returning("receipt-7"))).isEqualTo(Outcome.ABANDONED);

        assertThat(logged.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).isEqualTo(
                "Abandoned row 7 token 3 of owner instance-a: its outcome could not be persisted: "
                        + unreadable.getClass().getName());
    }

    @Test
    @Timeout(10)   // an unbounded walk of the cycle would hang, not fail
    void aCyclicCauseChainIsLoggedToABoundedDepth() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");
        first.initCause(second);
        second.initCause(first);
        repository.thenThrow(first).thenThrow(first).thenThrow(first);

        assertThat(process(returning("receipt-7"))).isEqualTo(Outcome.ABANDONED);

        assertThat(logged.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).isEqualTo(
                "Abandoned row 7 token 3 of owner instance-a: its outcome could not be persisted: "
                        + String.join(", caused by ", "java.lang.IllegalStateException",
                        "java.lang.IllegalArgumentException", "java.lang.IllegalStateException",
                        "java.lang.IllegalArgumentException", "java.lang.IllegalStateException",
                        "java.lang.IllegalArgumentException", "java.lang.IllegalStateException",
                        "java.lang.IllegalArgumentException"));
    }

    @Test
    void anInterruptWhileWaitingToRetryAbandons() {
        repository.thenThrow(new DataAccessResourceFailureException("Db2 unreachable"));
        ItemProcessor processor = new ItemProcessor(repository, recording(returning("receipt-7")), OWNER, NAMESPACE,
                SETTINGS, duration -> {
                    throw new InterruptedException();
                });

        assertThat(processor.process(ITEM, () -> false)).isEqualTo(Outcome.ABANDONED);

        assertThat(repository.writes()).hasSize(1);
        assertThat(Thread.currentThread().isInterrupted()).as("interrupt status restored").isTrue();
    }

    @Test
    void anInterruptDuringAPersistAttemptAbandonsWithoutRetrying() {
        repository.then(() -> {
            Thread.currentThread().interrupt();
            throw new DataAccessResourceFailureException("interrupted during connection acquisition");
        });

        assertThat(process(returning("receipt-7"))).isEqualTo(Outcome.ABANDONED);

        assertThat(repository.writes()).hasSize(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void anInterruptedCallWritesNothing() {
        assertThat(process(throwing(new InterruptedException()))).isEqualTo(Outcome.INTERRUPTED);

        assertThat(repository.writes()).isEmpty();
        assertThat(Thread.currentThread().isInterrupted()).as("interrupt status restored").isTrue();
    }

    @Test
    void aCallThatReturnsAfterItsThreadWasInterruptedWritesNothing() {
        ExternalService returnsAnyway = (key, token, payload, timeout) -> {
            Thread.currentThread().interrupt();
            return new CallResult("receipt-7");
        };

        assertThat(process(returnsAnyway)).isEqualTo(Outcome.INTERRUPTED);

        assertThat(repository.writes()).isEmpty();
    }

    @Test
    void aFailureWhoseMessageThrowsIsRecordedByItsClassName() {
        repository.thenReturn(PersistResult.RETRY_SCHEDULED);
        IllegalStateException thrown = new IllegalStateException("downstream said no") {
            @Override
            public String getMessage() {
                throw new IllegalStateException("message is broken");
            }
        };

        assertThat(process(throwing(thrown))).isEqualTo(Outcome.RETRY_SCHEDULED);

        assertThat(repository.writes()).containsExactly(
                new Write(RETRY_OR_FAIL, OWNER, CLAIM, thrown.getClass().getName()));
    }

    @Test
    void aFailureWhoseMessageThrowsAnErrorIsStillRecordedByItsClassName() {
        repository.thenReturn(PersistResult.RETRY_SCHEDULED);
        IllegalStateException thrown = new IllegalStateException("downstream said no") {
            @Override
            public String getMessage() {
                throw new AssertionError("sensitive-payload-from-accessor");
            }
        };

        assertThat(process(throwing(thrown))).isEqualTo(Outcome.RETRY_SCHEDULED);

        assertThat(repository.writes()).containsExactly(
                new Write(RETRY_OR_FAIL, OWNER, CLAIM, thrown.getClass().getName()));
    }

    @Test
    void aCallThatFailsBecauseItsThreadWasInterruptedWritesNothing() {
        ExternalService wrapsTheInterrupt = (key, token, payload, timeout) -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("request aborted");
        };

        assertThat(process(wrapsTheInterrupt)).isEqualTo(Outcome.INTERRUPTED);

        assertThat(repository.writes()).isEmpty();
    }

    @Test
    void rejectsAnInvalidOwnerOrNamespace() {
        assertThatThrownBy(() -> new ItemProcessor(repository, returning("r"), " ", NAMESPACE, SETTINGS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemProcessor(repository, returning("r"), OWNER, "Bad:Namespace", SETTINGS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("Bad:Namespace");
    }

    @Test
    void settingsComeFromTheProperties() {
        assertThat(ItemProcessor.Settings.from(new WorkQueueProperties()))
                .isEqualTo(new ItemProcessor.Settings(ofSeconds(30), 3, ofSeconds(1)));
        assertThat(SETTINGS).isEqualTo(new ItemProcessor.Settings(ofSeconds(3), 2, ofMillis(100)));
    }

    @Test
    void settingsRejectANonPositiveTimeoutOrANegativeRetryCountOrDelay() {
        assertThatThrownBy(() -> new ItemProcessor.Settings(Duration.ZERO, 2, ofMillis(100)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("externalCallTimeout");
        assertThatThrownBy(() -> new ItemProcessor.Settings(ofSeconds(3), -1, ofMillis(100)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("completionRetries");
        assertThatThrownBy(() -> new ItemProcessor.Settings(ofSeconds(3), 2, ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("completionRetryDelay");
    }

    private Outcome process(ExternalService service) {
        return processor(service).process(ITEM, () -> false);
    }

    private ItemProcessor processor(ExternalService service) {
        return new ItemProcessor(repository, recording(service), OWNER, NAMESPACE, SETTINGS, sleeps::add);
    }

    private ExternalService recording(ExternalService service) {
        return (key, token, payload, timeout) -> {
            calls.add(new Call(key, token, payload, timeout));
            return service.call(key, token, payload, timeout);
        };
    }

    private static ExternalService returning(String value) {
        return (key, token, payload, timeout) -> new CallResult(value);
    }

    private static ExternalService throwing(Exception failure) {
        return (key, token, payload, timeout) -> {
            throw failure;
        };
    }
}
