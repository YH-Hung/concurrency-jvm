package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DataAccessResourceFailureException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticsTest {

    @Test
    void aFailureIsDescribedByItsClassNameWithoutItsMessage() {
        assertThat(Diagnostics.describe(new IllegalStateException("payload-7")))
                .isEqualTo("java.lang.IllegalStateException");
    }

    @Test
    void causesAreListedWithTheirSqlCodes() {
        RuntimeException failure = new DataAccessResourceFailureException("could not store receipt-7",
                new SQLTransientConnectionException("payload-7 of order-7:charge", "08001", -4499));

        assertThat(Diagnostics.describe(failure)).isEqualTo(
                "org.springframework.dao.DataAccessResourceFailureException, caused by "
                        + "java.sql.SQLTransientConnectionException (SQLState 08001, error code -4499)");
    }

    @Test
    @Timeout(10)   // an unbounded walk of the cycle would hang, not fail
    void aCyclicCauseChainIsDescribedToABoundedDepth() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");
        first.initCause(second);
        second.initCause(first);

        assertThat(Diagnostics.describe(first).split(", caused by ")).hasSize(Diagnostics.MAX_CAUSES);
    }

    @Test
    void aCauseThatCannotBeReadEndsTheDescription() {
        RuntimeException unreadable = new IllegalStateException() {
            @Override
            public synchronized Throwable getCause() {
                throw new IllegalStateException("getCause is broken");
            }
        };

        assertThat(Diagnostics.describe(unreadable)).isEqualTo(unreadable.getClass().getName());
    }

    @Test
    void sqlCodesThatCannotBeReadAreLeftOut() {
        SQLException unreadable = new SQLException("payload-7") {
            @Override
            public String getSQLState() {
                throw new IllegalStateException("getSQLState is broken");
            }
        };

        assertThat(Diagnostics.describe(unreadable)).isEqualTo(unreadable.getClass().getName());
    }

    @Test
    void anErrorFromACauseAccessorIsContained() {
        RuntimeException unreadable = new IllegalStateException() {
            @Override
            public synchronized Throwable getCause() {
                throw new AssertionError("sensitive-payload-from-accessor");
            }
        };

        assertThat(Diagnostics.describe(unreadable)).isEqualTo(unreadable.getClass().getName());
    }

    @Test
    void anErrorFromAnSqlCodeAccessorIsContained() {
        SQLException unreadable = new SQLException("payload-7") {
            @Override
            public int getErrorCode() {
                throw new AssertionError("sensitive-payload-from-accessor");
            }
        };

        assertThat(Diagnostics.describe(unreadable)).isEqualTo(unreadable.getClass().getName());
    }
}
