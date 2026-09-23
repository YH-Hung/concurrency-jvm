package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayFilterTest {

    @Test
    void requiresAtLeastOneCriterion() {
        assertThatThrownBy(() -> new ReplayFilter(List.of(), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReplayFilter(null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnEmptyErrorText() {
        assertThatThrownBy(() -> new ReplayFilter(List.of(), "", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsAnySingleCriterionAndCopiesTheIds() {
        List<Long> ids = new ArrayList<>(List.of(1L, 2L));
        ReplayFilter byIds = new ReplayFilter(ids, null, null);
        ids.add(3L);

        assertThat(byIds.ids()).containsExactly(1L, 2L);
        assertThat(new ReplayFilter(null, "503", null).ids()).isEmpty();
        assertThat(new ReplayFilter(null, null, LocalDateTime.of(2026, 9, 1, 0, 0)).failedBefore()).isNotNull();
    }
}
