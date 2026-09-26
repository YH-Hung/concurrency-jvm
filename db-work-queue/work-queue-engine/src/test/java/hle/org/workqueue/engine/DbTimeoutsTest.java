package hle.org.workqueue.engine;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DbTimeoutsTest {

    /** IT column of spec §5.3. */
    private static final DbTimeouts IT = new DbTimeouts(ofMillis(500), ofSeconds(1), ofSeconds(2), ofSeconds(2), ofSeconds(1));

    @Test
    void defaultsMatchTheSpecTimingBudget() {
        assertThat(DbTimeouts.defaults())
                .isEqualTo(new DbTimeouts(ofSeconds(2), ofSeconds(3), ofSeconds(5), ofSeconds(8), ofSeconds(3)));
    }

    @Test
    void appliesPoolWaitLockTimeoutAndDriverPropertiesToHikari() {
        HikariConfig config = new HikariConfig();

        IT.applyTo(config);

        assertThat(config.getConnectionTimeout()).isEqualTo(500);
        assertThat(config.getValidationTimeout()).isEqualTo(250);
        assertThat(config.getConnectionInitSql()).isEqualTo("SET CURRENT LOCK TIMEOUT 1");
        assertThat(config.getDataSourceProperties())
                .containsEntry("loginTimeout", "1")
                .containsEntry("blockingReadConnectionTimeout", "2")
                .containsEntry("queryTimeoutInterruptProcessingMode", "2");
    }

    @Test
    void validationTimeoutIsHalfThePoolWaitButNeverBelowHikarisMinimum() {
        HikariConfig config = new HikariConfig();

        DbTimeouts.defaults().applyTo(config);

        assertThat(config.getValidationTimeout()).isEqualTo(1000);
    }

    @Test
    void worstCaseOperationIsPoolWaitPlusLoginPlusTransactionPlusRead() {
        assertThat(DbTimeouts.defaults().worstCaseOperation()).isEqualTo(ofSeconds(18));
        assertThat(IT.worstCaseOperation()).isEqualTo(ofMillis(5500));
    }

    @Test
    void exposesTheTransactionTimeoutInWholeSeconds() {
        assertThat(IT.transactionSeconds()).isEqualTo(2);
    }

    @Test
    void rejectsFractionalSecondsWhereJccOrDb2TakeWholeSeconds() {
        assertThatThrownBy(() -> new DbTimeouts(ofMillis(500), ofMillis(1500), ofSeconds(2), ofSeconds(2), ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("login");
        assertThatThrownBy(() -> new DbTimeouts(ofMillis(500), ofSeconds(1), ofMillis(2500), ofSeconds(2), ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("transaction");
        assertThatThrownBy(() -> new DbTimeouts(ofMillis(500), ofSeconds(1), ofSeconds(2), ofMillis(2500), ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("read");
        assertThatThrownBy(() -> new DbTimeouts(ofMillis(500), ofSeconds(1), ofSeconds(2), ofSeconds(2), ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lockWait");
    }

    @Test
    void rejectsZeroTimeouts() {
        assertThatThrownBy(() -> new DbTimeouts(ofMillis(500), ofSeconds(1), Duration.ZERO, ofSeconds(2), ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("transaction");
    }

    @Test
    void rejectsAPoolWaitBelowHikarisMinimum() {
        assertThatThrownBy(() -> new DbTimeouts(ofMillis(200), ofSeconds(1), ofSeconds(2), ofSeconds(2), ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("poolWait");
    }
}
