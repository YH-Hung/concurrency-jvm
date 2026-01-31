package hle.org;

import java.util.Arrays;

public final class Stats {
    private final int requests;
    private final int failures;
    private final long totalTimeNanos;
    private final double throughputPerSec;
    private final double avgMillis;
    private final double p50Millis;
    private final double p95Millis;

    private Stats(int requests,
                  int failures,
                  long totalTimeNanos,
                  double throughputPerSec,
                  double avgMillis,
                  double p50Millis,
                  double p95Millis) {
        this.requests = requests;
        this.failures = failures;
        this.totalTimeNanos = totalTimeNanos;
        this.throughputPerSec = throughputPerSec;
        this.avgMillis = avgMillis;
        this.p50Millis = p50Millis;
        this.p95Millis = p95Millis;
    }

    public static Stats from(long[] durationsNanos, int failures, long totalTimeNanos) {
        int requests = durationsNanos.length;
        double totalSeconds = totalTimeNanos / 1_000_000_000.0;
        double throughputPerSec = totalSeconds > 0 ? requests / totalSeconds : 0.0;

        long[] sorted = Arrays.copyOf(durationsNanos, durationsNanos.length);
        Arrays.sort(sorted);

        double avgMillis = requests > 0 ? nanosToMillis(sum(sorted) / (double) requests) : 0.0;
        double p50Millis = percentileMillis(sorted, 0.50);
        double p95Millis = percentileMillis(sorted, 0.95);

        return new Stats(requests, failures, totalTimeNanos, throughputPerSec, avgMillis, p50Millis, p95Millis);
    }

    private static long sum(long[] values) {
        long total = 0L;
        for (long value : values) {
            total += value;
        }
        return total;
    }

    private static double percentileMillis(long[] sorted, double percentile) {
        if (sorted.length == 0) {
            return 0.0;
        }
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        index = Math.max(0, Math.min(index, sorted.length - 1));
        return nanosToMillis(sorted[index]);
    }

    private static double nanosToMillis(double nanos) {
        return nanos / 1_000_000.0;
    }

    public int getRequests() {
        return requests;
    }

    public int getFailures() {
        return failures;
    }

    public long getTotalTimeNanos() {
        return totalTimeNanos;
    }

    public double getThroughputPerSec() {
        return throughputPerSec;
    }

    public double getAvgMillis() {
        return avgMillis;
    }

    public double getP50Millis() {
        return p50Millis;
    }

    public double getP95Millis() {
        return p95Millis;
    }
}
