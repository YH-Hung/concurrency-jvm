package hle.org.workqueue.engine;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Which FAILED rows an operator replays (spec §9.7). Criteria combine with AND; at least one is required,
 * so an empty filter cannot replay every failure by accident.
 *
 * @param ids               row ids; empty for no id criterion
 * @param lastErrorContains text LAST_ERROR must contain; null for no criterion
 * @param failedBefore      rows that became FAILED (UPDATED_AT) before this Db2 server local time; null for none
 */
public record ReplayFilter(List<Long> ids, String lastErrorContains, LocalDateTime failedBefore) {

    public ReplayFilter {
        ids = ids == null ? List.of() : List.copyOf(ids);
        if (lastErrorContains != null && lastErrorContains.isEmpty()) {
            throw new IllegalArgumentException("lastErrorContains must not be empty");
        }
        if (ids.isEmpty() && lastErrorContains == null && failedBefore == null) {
            throw new IllegalArgumentException("a replay filter needs ids, lastErrorContains or failedBefore");
        }
    }
}
