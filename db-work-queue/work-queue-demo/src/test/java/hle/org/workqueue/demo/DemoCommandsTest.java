package hle.org.workqueue.demo;
import java.sql.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class DemoCommandsTest {
    @Test void successRequiresEveryRequestedRowDone() {
        assertThat(new DemoCommands.Verification(10,10,0,0,0,0).succeeded()).isTrue();
        assertThat(new DemoCommands.Verification(10,9,0,0,0,1).succeeded()).isFalse();
        assertThat(new DemoCommands.Verification(10,9,1,0,0,0).succeeded()).isFalse();
    }
    @Test void queriesOnlyBoundBatchIdsAndCountsMissingRows() throws Exception {
        var fixture = new Fixture("DONE");
        var result = fixture.commands.verify(List.of(42L,99L), Duration.ofSeconds(1));
        assertThat(result).isEqualTo(new DemoCommands.Verification(2,1,0,0,0,1));
        verify(fixture.connection).prepareStatement("SELECT ID, STATUS FROM WORK_ITEM WHERE ID IN (?,?)");
        verify(fixture.jobs).setLong(1,42L); verify(fixture.jobs).setLong(2,99L);
        verify(fixture.jobs).setQueryTimeout(5);
        assertThat(fixture.clock.get()).isZero();
    }
    @Test void failedJobStopsImmediatelyAndPendingExpiresAtDeadline() throws Exception {
        var failed = new Fixture("FAILED");
        assertThat(failed.commands.verify(List.of(42L), Duration.ofSeconds(1)).failed()).isEqualTo(1);
        assertThat(failed.clock.get()).isZero();
        var pending = new Fixture("PENDING");
        assertThat(pending.commands.verify(List.of(42L), Duration.ofMillis(5)).succeeded()).isFalse();
        assertThat(pending.clock.get()).isEqualTo(Duration.ofMillis(5).toNanos());
    }
    @Test void rejectsInvalidInputBeforeDatabaseAccess() throws Exception {
        var ds = mock(DataSource.class); var commands = new DemoCommands(ds);
        assertThatThrownBy(() -> commands.seed(0)).isInstanceOf(IllegalArgumentException.class);
        for (var ids : List.of(List.<Long>of(), List.of(1L,1L), List.of(-1L)))
            assertThatThrownBy(() -> commands.verify(ids, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> commands.verify(List.of(1L), Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ds);
    }
    @Test void refusesOtherDatabasesBeforeModifyingAnything() throws Exception {
        var f = new Fixture("DONE"); when(f.server.getString(1)).thenReturn("PRODUCTION");
        assertThatThrownBy(() -> f.commands.seed(1)).isInstanceOf(IllegalStateException.class);
        verify(f.connection, never()).setAutoCommit(false);
    }
    static class Fixture {
        final DataSource source = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final PreparedStatement jobs = mock(PreparedStatement.class);
        final ResultSet server = mock(ResultSet.class);
        final AtomicLong clock = new AtomicLong();
        final DemoCommands commands;
        Fixture(String status) throws Exception {
            when(source.getConnection()).thenReturn(connection);
            var check = mock(PreparedStatement.class);
            when(connection.prepareStatement("VALUES CURRENT SERVER")).thenReturn(check);
            when(check.executeQuery()).thenReturn(server); when(server.next()).thenReturn(true);
            when(server.getString(1)).thenReturn("WORKQ");
            when(connection.prepareStatement(startsWith("SELECT ID, STATUS"))).thenReturn(jobs);
            when(jobs.executeQuery()).thenAnswer(inv -> {
                var rows = mock(ResultSet.class); when(rows.next()).thenReturn(true,false);
                when(rows.getLong(1)).thenReturn(42L); when(rows.getString(2)).thenReturn(status); return rows;
            });
            commands = new DemoCommands(source, clock::get, clock::addAndGet);
        }
    }
}
