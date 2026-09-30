package hle.org.workqueue.engine;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

/**
 * The engine's Micrometer meters (spec §9.6), all named {@code workqueue.*}. Each one reads the runner's or the
 * processor's state when the registry is scraped, so no meter is on a task's path. The timers are FunctionTimers:
 * they report a count and a total time, so a rate and a mean, but no maximum or percentiles. The backlog gauges
 * report NaN until the first sample. Phase 3's auto-configuration registers this as a bean, which Spring Boot binds
 * to the application's registry.
 */
final class WorkQueueMetrics implements MeterBinder {

    private final QueueRunner runner;
    private final ItemProcessor processor;

    WorkQueueMetrics(QueueRunner runner, ItemProcessor processor) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        counter(registry, "workqueue.claims", "Claim operations that returned, an empty claim included",
                QueueRunner::claims);
        timer(registry, "workqueue.claim.duration", "Claim operations, returned or failed", Tags.empty(),
                runner.claimTimes());
        counter(registry, "workqueue.claim.errors", "Claim operations that failed", QueueRunner::claimErrors);
        for (Outcome outcome : Outcome.values()) {
            FunctionCounter.builder("workqueue.outcomes", runner, r -> r.outcomes(outcome))
                    .description("Task ends, by outcome")
                    .tag("outcome", tagValue(outcome))
                    .register(registry);
        }
        for (ItemProcessor.CallStatus status : ItemProcessor.CallStatus.values()) {
            timer(registry, "workqueue.call.duration", "External calls, by how they ended",
                    Tags.of("result", tagValue(status)), processor.calls(status));
        }
        timer(registry, "workqueue.renewal.duration", "Renewal rounds that ran", Tags.empty(), runner.renewalTimes());
        counter(registry, "workqueue.renewal.errors", "Renewal rounds that failed", QueueRunner::renewalErrors);
        timeGauge(registry, "workqueue.renewal.lag", "Longest time since a renewal-eligible claim's lease was"
                + " written; 0 without one", r -> r.renewalLag().toNanos());
        counter(registry, "workqueue.claims.lost", "Claims renewal reported lost", QueueRunner::claimsLost);
        timeGauge(registry, "workqueue.db.last_success_age", "Time since an engine DB operation last succeeded",
                r -> r.dbLastSuccessAge().toNanos());
        gauge(registry, "workqueue.inflight", "Registered claims: running, or cancelled and not yet ended",
                QueueRunner::inflight);
        gauge(registry, "workqueue.permits.available", "Free task permits", QueueRunner::availablePermits);
        gauge(registry, "workqueue.tasks.hung", "Cancelled tasks still running hung-grace after the cancel",
                QueueRunner::hungTasks);
        counter(registry, "workqueue.registration.late", "Claims registered later than registration-allowance",
                QueueRunner::registrationsLate);
        counter(registry, "workqueue.invariant.violations", "Engine invariant breaches",
                QueueRunner::invariantViolations);
        backlog(registry, "pending", BacklogSample::pending);
        backlog(registry, "claimed", BacklogSample::claimed);
        backlog(registry, "failed", BacklogSample::failed);
        timeGauge(registry, "workqueue.backlog.oldest_pending_age", "How long the oldest claimable PENDING row has"
                + " waited (sampled)", r -> sampled(r, sample -> sample.oldestPendingAge().toNanos()));
        gauge(registry, "workqueue.claims.expired", "CLAIMED rows expired for more than one lease (sampled)",
                r -> sampled(r, BacklogSample::expiredClaims));
    }

    private void counter(MeterRegistry registry, String name, String description,
                         ToDoubleFunction<QueueRunner> count) {
        FunctionCounter.builder(name, runner, count).description(description).register(registry);
    }

    private static void timer(MeterRegistry registry, String name, String description, Tags tags,
                              OperationStats stats) {
        FunctionTimer.builder(name, stats, OperationStats::count, OperationStats::totalNanos, TimeUnit.NANOSECONDS)
                .description(description)
                .tags(tags)
                .register(registry);
    }

    private void gauge(MeterRegistry registry, String name, String description, ToDoubleFunction<QueueRunner> value) {
        Gauge.builder(name, runner, value).description(description).register(registry);
    }

    private void timeGauge(MeterRegistry registry, String name, String description,
                           ToDoubleFunction<QueueRunner> nanos) {
        TimeGauge.builder(name, runner, TimeUnit.NANOSECONDS, nanos).description(description).register(registry);
    }

    private void backlog(MeterRegistry registry, String status, ToDoubleFunction<BacklogSample> count) {
        Gauge.builder("workqueue.backlog", runner, r -> sampled(r, count))
                .description("Rows by unfinished status (sampled)")
                .tag("status", status)
                .register(registry);
    }

    private static double sampled(QueueRunner runner, ToDoubleFunction<BacklogSample> value) {
        BacklogSample sample = runner.backlog();
        return sample == null ? Double.NaN : value.applyAsDouble(sample);
    }

    private static String tagValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
