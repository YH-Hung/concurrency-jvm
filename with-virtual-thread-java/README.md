# Virtual Thread Java Concurrency Demo (JDK 25)

This project demonstrates how to perform **thousands of blocking I/O requests** (e.g., CORBA, RMI, HTTP) with **controlled concurrency** using **Java 25 virtual threads**, contrasting with the traditional thread pool approach in `pre-virtual-thread-java`.

## Key JDK 25 Features Used

| Feature | JEP | Status | Purpose |
|---------|-----|--------|---------|
| Virtual Threads | JEP 444 | Finalized | Lightweight threads for blocking I/O |
| Structured Concurrency | JEP 499 | Preview | Task lifecycle management |
| Scoped Values | JEP 487 | Preview | Context propagation (ThreadLocal alternative) |

## Key Differences from Pre-Virtual Thread Approach

| Aspect | Pre-Virtual Thread | With Virtual Threads |
|--------|-------------------|---------------------|
| Thread Pool Sizing | `corePoolSize`, `maxPoolSize` configuration | Not needed - virtual threads are cheap |
| Queue Management | Bounded queue with backpressure | Not needed - task submission is instant |
| Thread Creation | Expensive platform threads | Lightweight virtual threads |
| Blocking I/O | Blocks carrier thread | Virtual thread unmounts, carrier freed |
| Concurrency Control | Semaphore + thread pool | Semaphore only (still needed) |
| Code Complexity | Higher (async patterns) | Lower (synchronous style) |
| Resource Pooling | Essential | Still valuable for expensive resources |

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    VirtualPooledService                         │
│  (High-level API combining pool + virtual thread executor)      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────┐    ┌─────────────────────────────────┐ │
│  │   ResourcePool      │    │  VirtualThreadExecutor          │ │
│  │                     │    │                                 │ │
│  │  - Commons Pool2    │    │  - Virtual thread per task      │ │
│  │  - Validation       │    │  - Semaphore-based limiting     │ │
│  │  - Auto-cleanup     │    │  - No pool sizing needed        │ │
│  │                     │    │  - Statistics & monitoring      │ │
│  └─────────────────────┘    └─────────────────────────────────┘ │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │   StructuredTaskScope (Optional)                            ││
│  │                                                             ││
│  │  - Clear task ownership                                     ││
│  │  - Automatic cancellation on failure                        ││
│  │  - No orphaned threads                                      ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
              ┌───────────────────────────────┐
              │     PooledResource<T>         │
              │  (Your CORBA/RMI/HTTP client) │
              └───────────────────────────────┘
```

## Why Virtual Threads?

### Before (Platform Threads)
```java
// Need to carefully configure thread pool
ThreadPoolExecutor executor = new ThreadPoolExecutor(
    10,      // corePoolSize - minimum threads
    50,      // maxPoolSize - maximum threads  
    60L,     // keepAliveTime
    TimeUnit.SECONDS,
    new LinkedBlockingQueue<>(1000)  // bounded queue
);

// Blocking I/O blocks the platform thread
// Only 50 requests can be in-flight at once
```

### After (Virtual Threads)
```java
// Simple - just use virtual thread executor
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

// Each task gets its own virtual thread
// Thousands can run concurrently
// Blocking I/O doesn't waste carrier threads
```

## Quick Start

### Prerequisites

- **JDK 25** or later (with preview features enabled)
- Maven 3.6+

### Run the Demo

```bash
mvn compile exec:java -Dexec.args="--enable-preview"
```

### Run Tests

```bash
mvn test
```

## Usage Examples

### 1. Using VirtualPooledService (Recommended)

```java
// Create service - no thread pool sizing needed!
VirtualPooledService<MyCorbaClient, String> service = new VirtualPooledService<>(
    () -> new MyCorbaClient("corba://server:1234/service"),
    ResourcePoolConfig.builder()
        .maxPoolSize(10)  // Still pool expensive resources
        .build(),
    ExecutorConfig.builder()
        .concurrency(10)  // Semaphore limit for downstream
        .build()
);

// Execute thousands of requests with controlled parallelism
List<OperationSubmission<MyCorbaClient, String>> operations = 
    requests.stream()
        .map(req -> OperationSubmission.of(req.getId(), client -> client.execute(req)))
        .collect(Collectors.toList());

List<TaskResult<String>> results = service.executeAll(operations);

// Process results
long successCount = results.stream().filter(TaskResult::isSuccess).count();
System.out.println("Completed: " + successCount + "/" + results.size());

service.close();
```

### 2. Using Structured Concurrency (JDK 25 Preview)

```java
// Fail-fast: first failure cancels all other tasks
try {
    List<TaskResult<String>> results = service.executeAllStructured(operations);
    // All succeeded
} catch (Exception e) {
    // At least one failed - others were cancelled
}

// Collect all: get all results including failures
List<TaskResult<String>> results = service.executeAllStructuredCollectAll(operations);
long failures = results.stream().filter(TaskResult::isFailure).count();
```

### 3. Using VirtualThreadExecutor Directly

```java
VirtualThreadExecutor executor = new VirtualThreadExecutor(
    ExecutorConfig.builder()
        .concurrency(20)  // Limit concurrent downstream calls
        .build()
);

// Each task runs on its own virtual thread
List<CompletableFuture<TaskResult<String>>> futures = new ArrayList<>();
for (int i = 0; i < 10000; i++) {
    futures.add(executor.submit("task-" + i, () -> doBlockingIO()));
}

// Virtual threads handle blocking efficiently
List<TaskResult<String>> results = executor.awaitAll(futures);
```

## Configuration

### ExecutorConfig (Simplified)

| Option | Default | Description |
|--------|---------|-------------|
| `concurrency` | 10 | Max concurrent task executions (semaphore) |
| `taskTimeout` | 5min | Max time per task |
| `threadNamePrefix` | "virtual-executor" | Thread naming for debugging |

**Note:** Unlike the pre-virtual-thread version, there's no need to configure:
- `corePoolSize` / `maxPoolSize` - virtual threads are cheap
- `queueCapacity` - task submission doesn't block

### ResourcePoolConfig (Unchanged)

| Option | Default | Description |
|--------|---------|-------------|
| `minPoolSize` | 2 | Minimum idle resources |
| `maxPoolSize` | 10 | Maximum resources |
| `maxWaitTime` | 30s | Time to wait for a resource |
| `testOnBorrow` | true | Validate before use |

Resource pooling remains valuable for expensive resources like CORBA connections.

## Spring Integration

```java
@Configuration
public class ConcurrencyConfig {

    @Bean(destroyMethod = "close")
    public VirtualPooledService<MyCorbaClient, String> corbaService(
            @Value("${corba.pool.size:10}") int poolSize,
            @Value("${corba.concurrency:10}") int concurrency) {
        
        return new VirtualPooledService<>(
            () -> new MyCorbaClient(corbaEndpoint),
            ResourcePoolConfig.builder()
                .maxPoolSize(poolSize)
                .build(),
            ExecutorConfig.builder()
                .concurrency(concurrency)
                .threadNamePrefix("corba-vt")
                .build()
        );
    }
}

@Service
public class MyService {
    
    @Autowired
    private VirtualPooledService<MyCorbaClient, String> corbaService;

    public List<String> processBatch(List<Request> requests) {
        return corbaService.executeAll(
            requests.stream()
                .map(r -> OperationSubmission.of(r.getId(), c -> c.execute(r)))
                .collect(Collectors.toList())
        ).stream()
            .map(r -> r.getOrDefault("error"))
            .collect(Collectors.toList());
    }
}
```

## How Virtual Threads Work

### Carrier Thread Multiplexing

```
Virtual Threads:    VT1(blocking)  VT2(blocking)  VT3(running)  VT4(blocking)
                         ↓              ↓              ↓              ↓
                    [unmounted]    [unmounted]    [mounted]     [unmounted]
                                                      ↓
Carrier Thread:                                   [Platform Thread]
```

When a virtual thread performs blocking I/O:
1. The virtual thread is **unmounted** from the carrier thread
2. The carrier thread is freed to run other virtual threads
3. When I/O completes, the virtual thread is **remounted** (possibly on a different carrier)

This allows thousands of concurrent blocking operations with only a handful of carrier threads.

### Why Semaphore is Still Needed

Even with virtual threads, you still need to limit concurrency to:
- Protect downstream services from being overwhelmed
- Respect connection limits on remote systems
- Control resource consumption

```java
// Without semaphore: 10,000 concurrent connections to CORBA server = crash
// With semaphore: At most 10 concurrent connections
Semaphore concurrencyLimiter = new Semaphore(10);
```

## Project Structure

```
src/main/java/hle/org/
├── pool/
│   ├── PooledResource.java          # Interface for poolable resources
│   ├── PooledResourceFactory.java   # Commons Pool2 integration
│   ├── ResourcePool.java            # High-level pool wrapper
│   ├── ResourcePoolConfig.java      # Pool configuration
│   └── ResourcePoolException.java   # Pool exceptions
├── executor/
│   ├── VirtualThreadExecutor.java   # Virtual thread executor
│   ├── ExecutorConfig.java          # Simplified config
│   └── TaskResult.java              # Task result wrapper
├── client/
│   ├── RemoteClient.java            # Generic remote client interface
│   ├── RemoteClientException.java   # Client exceptions
│   └── SimulatedCorbaClient.java    # Test implementation
├── service/
│   └── VirtualPooledService.java    # Combined pool + virtual executor
└── demo/
    └── VirtualThreadDemo.java       # Demonstration
```

## Comparison with pre-virtual-thread-java

| Metric | Pre-Virtual Thread | With Virtual Threads |
|--------|-------------------|---------------------|
| Lines of code (Executor) | ~460 | ~320 |
| Configuration options | 8 | 3 |
| Thread pool tuning | Required | Not needed |
| Maximum concurrency | Limited by threads | Virtually unlimited |
| Memory per connection | ~1MB (stack) | ~1KB (stack) |
| Blocking I/O efficiency | Low | High |

## Requirements

- **Java 25** (with `--enable-preview` for Structured Concurrency)
- Maven 3.6+

## Dependencies

- Apache Commons Pool2 (for resource pooling)
- SLF4J (API + Simple runtime logger)
- JUnit 5 (for testing)

## License

MIT
