package hle.org;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public final class App {
    private static final int DEFAULT_REQUESTS = 5_000;
    private static final int DEFAULT_CONCURRENCY = 100;
    private static final int DEFAULT_POOL_SIZE = 50;
    private static final int DEFAULT_QUEUE_SIZE = 1_000;
    private static final int DEFAULT_SLEEP_MS = 20;
    private static final int DEFAULT_CPU_ITERATIONS = 1_000;

    private App() {
    }

    public static void main(String[] args) {
        Map<String, String> options;
        try {
            options = parseArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printUsage();
            return;
        }

        if (options.containsKey("help")) {
            printUsage();
            return;
        }

        int requests = getIntOption(options, "requests", DEFAULT_REQUESTS);
        int concurrency = getIntOption(options, "concurrency", DEFAULT_CONCURRENCY);
        int poolSize = getIntOption(options, "pool-size", DEFAULT_POOL_SIZE);
        int queueSize = getIntOption(options, "queue-size", DEFAULT_QUEUE_SIZE);
        int sleepMs = getIntOption(options, "sleep-ms", DEFAULT_SLEEP_MS);
        int cpuIterations = getIntOption(options, "cpu-iterations", DEFAULT_CPU_ITERATIONS);
        int minIdle = getIntOption(options, "min-idle", Math.min(5, poolSize));
        int maxIdle = getIntOption(options, "max-idle", poolSize);

        if (!validateOptions(requests, concurrency, poolSize, queueSize, sleepMs, cpuIterations, minIdle, maxIdle)) {
            return;
        }
        if (concurrency > requests) {
            concurrency = requests;
        }

        GenericObjectPoolConfig<SimulatedResource> config = new GenericObjectPoolConfig<>();
        config.setMaxTotal(poolSize);
        config.setMaxIdle(maxIdle);
        config.setMinIdle(minIdle);
        config.setBlockWhenExhausted(true);
        config.setMaxWait(Duration.ofSeconds(30));

        try (ResourcePool<SimulatedResource> pool = new ResourcePool<>(new SimulatedResourceFactory(), config)) {
            RequestRunner runner = new RequestRunner(pool);
            RunResult result = runner.run(requests, concurrency, queueSize, sleepMs, cpuIterations);
            printSummary(requests, concurrency, poolSize, queueSize, sleepMs, cpuIterations, minIdle, maxIdle, result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Run interrupted.");
        } catch (Exception e) {
            System.err.println("Run failed: " + e.getMessage());
        }
    }

    private static boolean validateOptions(int requests,
                                           int concurrency,
                                           int poolSize,
                                           int queueSize,
                                           int sleepMs,
                                           int cpuIterations,
                                           int minIdle,
                                           int maxIdle) {
        if (requests <= 0) {
            System.err.println("requests must be > 0");
            return false;
        }
        if (concurrency <= 0) {
            System.err.println("concurrency must be > 0");
            return false;
        }
        if (poolSize <= 0) {
            System.err.println("pool-size must be > 0");
            return false;
        }
        if (queueSize <= 0) {
            System.err.println("queue-size must be > 0");
            return false;
        }
        if (sleepMs < 0) {
            System.err.println("sleep-ms must be >= 0");
            return false;
        }
        if (cpuIterations < 0) {
            System.err.println("cpu-iterations must be >= 0");
            return false;
        }
        if (minIdle < 0 || maxIdle < 0) {
            System.err.println("min-idle/max-idle must be >= 0");
            return false;
        }
        if (maxIdle < minIdle) {
            System.err.println("max-idle must be >= min-idle");
            return false;
        }
        return true;
    }

    private static void printSummary(int requests,
                                     int concurrency,
                                     int poolSize,
                                     int queueSize,
                                     int sleepMs,
                                     int cpuIterations,
                                     int minIdle,
                                     int maxIdle,
                                     RunResult result) {
        Stats stats = result.getStats();
        double totalSeconds = stats.getTotalTimeNanos() / 1_000_000_000.0;

        System.out.println("=== Simulation Summary ===");
        System.out.printf("requests=%d, concurrency=%d, poolSize=%d, queueSize=%d%n",
                requests, concurrency, poolSize, queueSize);
        System.out.printf("sleepMs=%d, cpuIterations=%d, minIdle=%d, maxIdle=%d%n",
                sleepMs, cpuIterations, minIdle, maxIdle);
        System.out.printf("totalTime=%.2fs, throughput=%.2f req/s, failures=%d%n",
                totalSeconds, stats.getThroughputPerSec(), stats.getFailures());
        System.out.printf("latencyMs: avg=%.2f, p50=%.2f, p95=%.2f%n",
                stats.getAvgMillis(), stats.getP50Millis(), stats.getP95Millis());
        System.out.printf("pool: active=%d, idle=%d%n",
                result.getActiveResources(), result.getIdleResources());
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                options.put("help", "true");
                continue;
            }
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
            String key = arg.substring(2);
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for --" + key);
            }
            String value = args[++i];
            options.put(key, value);
        }
        return options;
    }

    private static int getIntOption(Map<String, String> options, String key, int defaultValue) {
        String raw = options.get(key);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for --" + key + ": " + raw);
        }
    }

    private static void printUsage() {
        System.out.println("Usage: java -cp <classpath> hle.org.App [options]");
        System.out.println("Options:");
        System.out.println("  --requests <int>        Total number of requests (default: " + DEFAULT_REQUESTS + ")");
        System.out.println("  --concurrency <int>     Maximum concurrent in-flight requests (default: " + DEFAULT_CONCURRENCY + ")");
        System.out.println("  --pool-size <int>       Max resources in pool (default: " + DEFAULT_POOL_SIZE + ")");
        System.out.println("  --queue-size <int>      Queue size before backpressure (default: " + DEFAULT_QUEUE_SIZE + ")");
        System.out.println("  --sleep-ms <int>        Blocking sleep per request (default: " + DEFAULT_SLEEP_MS + ")");
        System.out.println("  --cpu-iterations <int>  CPU work iterations per request (default: " + DEFAULT_CPU_ITERATIONS + ")");
        System.out.println("  --min-idle <int>        Min idle resources (default: min(5, pool-size))");
        System.out.println("  --max-idle <int>        Max idle resources (default: pool-size)");
        System.out.println("  --help                  Show this help");
    }
}
