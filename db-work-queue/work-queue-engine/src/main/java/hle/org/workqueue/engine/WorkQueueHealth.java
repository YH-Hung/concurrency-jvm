package hle.org.workqueue.engine;

import org.springframework.boot.health.contributor.Health;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Liveness and readiness of one instance (spec §9.6), read from its runner whenever a probe asks. Phase 3's
 * auto-configuration registers {@link #liveness()} and {@link #readiness()} as health indicators in the probe groups,
 * and adds SchemaCheck to readiness. An idle, healthy instance stays ready indefinitely.
 */
final class WorkQueueHealth {

    /**
     * @param lease            readiness turns DOWN once renewal.lag passes it: the instance may be losing claims
     * @param dbStalenessLimit readiness turns DOWN once db.last_success_age passes it
     */
    record Settings(Duration lease, Duration dbStalenessLimit) {

        Settings {
            Durations.requirePositive("lease", lease);
            Durations.requirePositive("dbStalenessLimit", dbStalenessLimit);
        }

        static Settings from(WorkQueueProperties properties) {
            return new Settings(properties.getLeaseDuration(), properties.getDbStalenessLimit());
        }
    }

    private final Supplier<EngineSnapshot> snapshots;
    private final Settings settings;

    WorkQueueHealth(Supplier<EngineSnapshot> snapshots, Settings settings) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * DOWN when hung tasks reach hung-task-limit, after any invariant violation, or when any of the runner's five
     * loops has died: the orchestrator's restart is the remedy for each.
     */
    Health liveness() {
        EngineSnapshot snapshot = snapshots.get();
        int hungTasks = snapshot.execution().hungTasks();
        long invariantViolations = snapshot.execution().invariantViolations();
        List<String> deadLoops = snapshot.runtime().deadLoops();
        boolean live = !snapshot.execution().hungTaskLimitReached() && invariantViolations == 0 && deadLoops.isEmpty();
        return (live ? Health.up() : Health.down())
                .withDetail("hungTasks", hungTasks)
                .withDetail("invariantViolations", invariantViolations)
                .withDetail("deadLoops", deadLoops)
                .build();
    }

    /**
     * DOWN from the start of stop, when renewal.lag passes the lease (only while claims are held; the one failed
     * round B2 tolerates stays below it), and when db.last_success_age passes db-staleness-limit.
     */
    Health readiness() {
        EngineSnapshot snapshot = snapshots.get();
        boolean stopping = snapshot.runtime().stopping();
        Duration renewalLag = snapshot.execution().renewalLag();
        Duration dbLastSuccessAge = snapshot.dbLastSuccessAge();
        boolean ready = !stopping && renewalLag.compareTo(settings.lease()) <= 0
                && dbLastSuccessAge.compareTo(settings.dbStalenessLimit()) <= 0;
        return (ready ? Health.up() : Health.down())
                .withDetail("stopping", stopping)
                .withDetail("renewalLag", Durations.seconds(renewalLag))
                .withDetail("dbLastSuccessAge", Durations.seconds(dbLastSuccessAge))
                .build();
    }
}
