package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimedItemTest {

    @Test
    void toStringShowsOnlyTheIdAndToken() {
        ClaimedItem item = new ClaimedItem(1, "op-secret", "payload-secret", 7);

        assertThat(item).hasToString("ClaimedItem[id=1, claimToken=7]");
    }
}
