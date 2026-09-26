package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenewalResultTest {

    private static final ClaimKey A = new ClaimKey(1, 1);
    private static final ClaimKey B = new ClaimKey(2, 1);
    private static final ClaimKey C = new ClaimKey(3, 1);

    @Test
    void holdsCopiesOfItsSets() {
        Set<ClaimKey> renewed = new HashSet<>(Set.of(A));

        RenewalResult result = new RenewalResult(renewed, Set.of(B), Set.of(C));
        renewed.add(B);

        assertThat(result.renewed()).containsExactly(A);
        assertThat(result.ended()).containsExactly(B);
        assertThat(result.lost()).containsExactly(C);
    }

    @Test
    void rejectsAClaimInMoreThanOneSet() {
        assertThatThrownBy(() -> new RenewalResult(Set.of(A), Set.of(A), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RenewalResult(Set.of(A), Set.of(), Set.of(A)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RenewalResult(Set.of(), Set.of(A), Set.of(A)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nothingIsEmpty() {
        assertThat(RenewalResult.NOTHING).isEqualTo(new RenewalResult(Set.of(), Set.of(), Set.of()));
    }
}
