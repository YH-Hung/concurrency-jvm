package hle.org.workqueue.engine;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import java.util.function.Supplier;

/**
 * The engine's Micrometer meters (spec §9.6), all named {@code workqueue.*}. Each one reads the runner's or the
 * processor's state when the registry is scraped, so no meter is on a task's path. The timers are FunctionTimers:
 * they report a count and a total time, so a rate and a mean, but no maximum or percentiles. The backlog gauges
 * report NaN until the first sample, then the latest successful one, which {@code backlog.sample_age} dates. Phase 3's
 * auto-configuration registers this as a bean, which Spring Boot binds to the application's registry.
 */
final class WorkQueueMetrics implements MeterBinder {

    private final Supplier<EngineSnapshot> snapshots;
    private final Supplier<Map<EngineSnapshot.CallStatus, OperationStats.Totals>> callStatistics;

    WorkQueueMetrics(Supplier<EngineSnapshot> snapshots,
                     Supplier<Map<EngineSnapshot.CallStatus, OperationStats.Totals>> callStatistics) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.callStatistics = Objects.requireNonNull(callStatistics, "callStatistics");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        counter(registry, "workqueue.claims", "Claim operations that returned, an empty claim included",
                s -> s.execution().claims());
        counter(registry, "workqueue.claim.rows", "Rows that claim operations returned", s -> s.execution().claimedRows());
        timer(registry, "workqueue.claim.duration", "Claim operations, returned or failed", Tags.empty(),
                () -> snapshots.get().execution().claimTimes());
        counter(registry, "workqueue.claim.errors", "Claim operations that failed", s -> s.execution().claimErrors());
        for (Outcome outcome : Outcome.values()) {
            FunctionCounter.builder("workqueue.outcomes", snapshots, ignored -> snapshots.get().execution().outcomes().get(outcome))
                    .description("Task ends, by outcome")
                    .tag("outcome", tagValue(outcome))
                    .register(registry);
        }
        for (EngineSnapshot.CallStatus status : EngineSnapshot.CallStatus.values()) {
            timer(registry, "workqueue.call.duration", "External calls, by how they ended",
                    Tags.of("result", tagValue(status)), () -> callStatistics.get().get(status));
        }
        timer(registry, "workqueue.renewal.duration", "Renewal rounds that ran", Tags.empty(), () -> snapshots.get().execution().renewalTimes());
        counter(registry, "workqueue.renewal.errors", "Renewal rounds that failed", s -> s.execution().renewalErrors());
        timeGauge(registry, "workqueue.renewal.lag", "Longest time since a renewal-eligible claim's lease was"
                + " written; 0 without one", s -> s.execution().renewalLag().toNanos());
        counter(registry, "workqueue.claims.lost", "Claims renewal reported lost", s -> s.execution().claimsLost());
        timeGauge(registry, "workqueue.db.last_success_age", "Time since an engine DB operation last succeeded",
                s -> s.dbLastSuccessAge().toNanos());
        gauge(registry, "workqueue.inflight", "Registered claims: running, or cancelled and not yet ended",
                s -> s.execution().inflight());
        gauge(registry, "workqueue.permits.available", "Free task permits", s -> s.execution().availablePermits());
        gauge(registry, "workqueue.tasks.hung", "Cancelled tasks still running hung-grace after the cancel",
                s -> s.execution().hungTasks());
        counter(registry, "workqueue.registration.late", "Claims registered later than registration-allowance",
                s -> s.execution().registrationsLate());
        counter(registry, "workqueue.invariant.violations", "Engine invariant breaches",
                s -> s.execution().invariantViolations());
        backlog(registry, "pending", BacklogSample::pending);
        backlog(registry, "claimed", BacklogSample::claimed);
        backlog(registry, "failed", BacklogSample::failed);
        timeGauge(registry, "workqueue.backlog.oldest_pending_age", "How long the oldest claimable PENDING row has"
                + " waited (sampled)", r -> sampled(r, sample -> sample.oldestPendingAge().toNanos()));
        gauge(registry, "workqueue.claims.expired", "CLAIMED rows expired for more than one lease (sampled)",
                r -> sampled(r, BacklogSample::expiredClaims));
        timeGauge(registry, "workqueue.backlog.sample_age", "Time since the latest successful backlog sample,"
                + " which the sampled gauges read", s -> s.backlog().sampleAge().toNanos());
        counter(registry, "workqueue.backlog.sample.errors", "Backlog samples that failed",
                s -> s.backlog().sampleErrors());
    }

    // Micrometer weakly references meter sources: callbacks retain their suppliers so binding stays live.
    private void counter(MeterRegistry registry, String name, String description,
                         ToDoubleFunction<EngineSnapshot> count) {
        FunctionCounter.builder(name, snapshots, ignored -> count.applyAsDouble(snapshots.get()))
                .description(description).register(registry);
    }

    private static void timer(MeterRegistry registry, String name, String description, Tags tags,
                              Supplier<OperationStats.Totals> stats) {
        FunctionTimer.builder(name, stats, ignored -> stats.get().count(), ignored -> stats.get().totalNanos(), TimeUnit.NANOSECONDS)
                .description(description)
                .tags(tags)
                .register(registry);
    }

    private void gauge(MeterRegistry registry, String name, String description, ToDoubleFunction<EngineSnapshot> value) {
        Gauge.builder(name, snapshots, ignored -> value.applyAsDouble(snapshots.get()))
                .description(description).register(registry);
    }

    private void timeGauge(MeterRegistry registry, String name, String description,
                           ToDoubleFunction<EngineSnapshot> nanos) {
        TimeGauge.builder(name, snapshots, TimeUnit.NANOSECONDS, ignored -> nanos.applyAsDouble(snapshots.get()))
                .description(description).register(registry);
    }

    private void backlog(MeterRegistry registry, String status, ToDoubleFunction<BacklogSample> count) {
        Gauge.builder("workqueue.backlog", snapshots, ignored -> sampled(snapshots.get(), count))
                .description("Rows by unfinished status (sampled)")
                .tag("status", status)
                .register(registry);
    }

    private static double sampled(EngineSnapshot snapshot, ToDoubleFunction<BacklogSample> value) {
        BacklogSample sample = snapshot.backlog().latest();
        return sample == null ? Double.NaN : value.applyAsDouble(sample);
    }

    private static String tagValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
