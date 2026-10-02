package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineSnapshotTest {
    @Test
    void retainedCollectionsAreCopiesAndRejectMutation() {
        Map<Outcome, Long> outcomes = new EnumMap<>(Outcome.class);
        outcomes.put(Outcome.COMPLETED, 1L);
        EngineSnapshot.Execution execution = new EngineSnapshot.Execution(4, 0, 0, false, Duration.ZERO,
                0, 0, 0, 0, 0, 0, 0, new OperationStats.Totals(0, 0), new OperationStats.Totals(0, 0), outcomes);
        List<String> dead = new ArrayList<>(List.of("poll"));
        EngineSnapshot.Runtime runtime = new EngineSnapshot.Runtime(true, false, dead);
        outcomes.put(Outcome.COMPLETED, 2L);
        dead.clear();

        assertThat(execution.outcomes()).containsEntry(Outcome.COMPLETED, 1L);
        assertThat(runtime.deadLoops()).containsExactly("poll");
        assertThatThrownBy(() -> execution.outcomes().put(Outcome.COMPLETED, 3L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> runtime.deadLoops().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void timingTotalsStayUnchangedAfterAnotherOperation() {
        OperationStats stats = new OperationStats();
        stats.record(10);
        OperationStats.Totals before = stats.snapshot();
        stats.record(20);

        assertThat(before).isEqualTo(new OperationStats.Totals(1, 10));
        assertThat(stats.snapshot()).isEqualTo(new OperationStats.Totals(2, 30));
    }
}
