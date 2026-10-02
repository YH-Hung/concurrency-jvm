package hle.org.workqueue.engine;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Read-only concurrent observations, never a globally atomic transaction or a view of mutable handles. */
record EngineSnapshot(Execution execution, Backlog backlog, Runtime runtime, Duration dbLastSuccessAge) {

    record Execution(int availablePermits, int inflight, int hungTasks, boolean hungTaskLimitReached,
                     Duration renewalLag, long claims, long claimedRows, long claimErrors, long renewalErrors,
                     long claimsLost, long registrationsLate, long invariantViolations,
                     OperationStats.Totals claimTimes, OperationStats.Totals renewalTimes, Map<Outcome, Long> outcomes) {
        Execution {
            outcomes = Map.copyOf(outcomes);
        }
    }

    record Backlog(BacklogSample latest, Duration sampleAge, long sampleErrors) {}

    record Runtime(boolean running, boolean stopping, List<String> deadLoops) {
        Runtime {
            deadLoops = List.copyOf(deadLoops);
        }
    }

    enum CallStatus { OK, ERROR, TIMEOUT, INTERRUPTED }
}
