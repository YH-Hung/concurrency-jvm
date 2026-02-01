package hle.org.client;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A simulated CORBA client for testing purposes.
 * This client simulates blocking I/O operations with configurable latency
 * and failure rates.
 * 
 * <p>Features:
 * <ul>
 *   <li>Configurable response time (simulates network latency)</li>
 *   <li>Configurable failure rate (simulates service errors)</li>
 *   <li>Thread-safe operation counter</li>
 *   <li>Console logging for observability</li>
 *   <li>Virtual thread awareness for JDK 21+</li>
 * </ul>
 */
public class SimulatedCorbaClient implements RemoteClient<String, String> {

    private static final AtomicInteger GLOBAL_REQUEST_COUNT = new AtomicInteger(0);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    
    private final String id;
    private final String endpoint;
    private final long minLatencyMs;
    private final long maxLatencyMs;
    private final double failureRate;
    private final AtomicBoolean connected;
    private final AtomicInteger requestCount;
    private final boolean verbose;

    /**
     * Creates a new simulated CORBA client with default settings.
     * Default: 50-200ms latency, 5% failure rate.
     */
    public SimulatedCorbaClient(String endpoint) {
        this(endpoint, 50, 200, 0.05, true);
    }

    /**
     * Creates a new simulated CORBA client with custom settings.
     * 
     * @param endpoint the simulated endpoint name
     * @param minLatencyMs minimum response time in milliseconds
     * @param maxLatencyMs maximum response time in milliseconds
     * @param failureRate probability of failure (0.0 to 1.0)
     * @param verbose whether to log to console
     */
    public SimulatedCorbaClient(String endpoint, long minLatencyMs, long maxLatencyMs, 
                                 double failureRate, boolean verbose) {
        this.id = "corba-client-" + UUID.randomUUID().toString().substring(0, 8);
        this.endpoint = endpoint;
        this.minLatencyMs = minLatencyMs;
        this.maxLatencyMs = maxLatencyMs;
        this.failureRate = failureRate;
        this.connected = new AtomicBoolean(true);
        this.requestCount = new AtomicInteger(0);
        this.verbose = verbose;
        
        if (verbose) {
            log("Created client");
        }
    }

    @Override
    public String execute(String request) throws RemoteClientException {
        if (!connected.get()) {
            throw new RemoteClientException("Client is not connected", true);
        }

        int globalCount = GLOBAL_REQUEST_COUNT.incrementAndGet();
        int localCount = requestCount.incrementAndGet();
        Thread currentThread = Thread.currentThread();
        String threadInfo = formatThreadInfo(currentThread);

        if (verbose) {
            log(String.format("Executing request #%d (global: #%d) on %s: %s", 
                    localCount, globalCount, threadInfo, request));
        }

        // Simulate blocking I/O with configurable latency
        // Virtual threads will unmount from carrier thread during this sleep
        long latencyRange = maxLatencyMs - minLatencyMs;
        long latency = latencyRange > 0 
                ? minLatencyMs + ThreadLocalRandom.current().nextLong(latencyRange + 1)
                : minLatencyMs;
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteClientException("Request interrupted", e, true);
        }

        // Simulate random failures
        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            String errorMsg = String.format("Simulated CORBA error for request: %s", request);
            if (verbose) {
                log("FAILED: " + errorMsg);
            }
            throw new RemoteClientException(errorMsg, true);
        }

        String response = String.format("Response[client=%s, request=%s, latency=%dms]", 
                id, request, latency);
        
        if (verbose) {
            log(String.format("Completed request #%d in %dms", localCount, latency));
        }

        return response;
    }

    /**
     * Formats thread information including virtual thread status.
     */
    private String formatThreadInfo(Thread thread) {
        if (thread.isVirtual()) {
            return String.format("VirtualThread[%s]", thread.getName());
        } else {
            return String.format("PlatformThread[%s]", thread.getName());
        }
    }

    @Override
    public String getEndpoint() {
        return endpoint;
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public boolean isValid() {
        return connected.get();
    }

    @Override
    public void reset() {
        // Nothing to reset in this simulation
    }

    @Override
    public void close() {
        if (connected.compareAndSet(true, false)) {
            if (verbose) {
                log(String.format("Closed after %d requests", requestCount.get()));
            }
        }
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Gets the number of requests this client has processed.
     */
    public int getRequestCount() {
        return requestCount.get();
    }

    /**
     * Gets the global request count across all clients.
     */
    public static int getGlobalRequestCount() {
        return GLOBAL_REQUEST_COUNT.get();
    }

    /**
     * Resets the global request counter (for testing).
     */
    public static void resetGlobalCounter() {
        GLOBAL_REQUEST_COUNT.set(0);
    }

    private void log(String message) {
        System.out.println(String.format("[%s][%s] %s", 
                LocalTime.now().format(TIME_FORMATTER),
                id, message));
    }

    /**
     * Builder for creating SimulatedCorbaClient with fluent API.
     */
    public static class Builder {
        private String endpoint = "corba://localhost:1234/service";
        private long minLatencyMs = 50;
        private long maxLatencyMs = 200;
        private double failureRate = 0.05;
        private boolean verbose = true;

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder latency(long minMs, long maxMs) {
            this.minLatencyMs = minMs;
            this.maxLatencyMs = maxMs;
            return this;
        }

        public Builder failureRate(double rate) {
            this.failureRate = rate;
            return this;
        }

        public Builder verbose(boolean verbose) {
            this.verbose = verbose;
            return this;
        }

        public SimulatedCorbaClient build() {
            return new SimulatedCorbaClient(endpoint, minLatencyMs, maxLatencyMs, 
                    failureRate, verbose);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
