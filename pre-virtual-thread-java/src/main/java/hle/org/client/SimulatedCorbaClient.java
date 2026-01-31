package hle.org.client;

import java.util.Random;
import java.util.UUID;
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
 * </ul>
 */
public class SimulatedCorbaClient implements RemoteClient<String, String> {

    private static final AtomicInteger GLOBAL_REQUEST_COUNT = new AtomicInteger(0);
    
    private final String id;
    private final String endpoint;
    private final long minLatencyMs;
    private final long maxLatencyMs;
    private final double failureRate;
    private final Random random;
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
        this.random = new Random();
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
        String threadName = Thread.currentThread().getName();

        if (verbose) {
            log(String.format("Executing request #%d (global: #%d) on %s: %s", 
                    localCount, globalCount, threadName, request));
        }

        // Simulate blocking I/O with configurable latency
        long latency = minLatencyMs + (long) (random.nextDouble() * (maxLatencyMs - minLatencyMs));
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteClientException("Request interrupted", e, true);
        }

        // Simulate random failures
        if (random.nextDouble() < failureRate) {
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
                java.time.LocalTime.now().toString().substring(0, 12),
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
