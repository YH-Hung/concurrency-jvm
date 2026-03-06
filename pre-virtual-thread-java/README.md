# Pre-Virtual Thread Java Concurrency Demo

This project demonstrates how to perform **thousands of blocking I/O requests** (e.g., CORBA, RMI, HTTP) with **controlled concurrency** in Java **before Java 21** (without virtual threads), while preventing **thread starvation**.

## Key Features

- **Bounded Concurrency**: Control exactly how many operations run in parallel
- **Resource Pooling**: Efficiently reuse expensive resources (like CORBA clients)
- **Backpressure**: Prevent memory exhaustion with bounded queues
- **Thread Starvation Prevention**: Dedicated executor threads for I/O operations
- **Production Ready**: Designed for Spring/JBoss integration
- **Generic & Reusable**: Not coupled to any specific technology

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    PooledExecutorService                        │
│  (High-level API combining pool + executor)                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────┐    ┌─────────────────────────────────┐ │
│  │   ResourcePool      │    │  BoundedConcurrencyExecutor     │ │
│  │                     │    │                                 │ │
│  │  - Commons Pool2    │    │  - Semaphore-based limiting     │ │
│  │  - Validation       │    │  - Bounded work queue           │ │
│  │  - Auto-cleanup     │    │  - Task result tracking         │ │
│  │                     │    │  - Statistics & monitoring      │ │
│  └─────────────────────┘    └─────────────────────────────────┘ │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
              ┌───────────────────────────────┐
              │     PooledResource<T>         │
              │  (Your CORBA/RMI/HTTP client) │
              └───────────────────────────────┘
```

## Quick Start

### Run the Demo

```bash
mvn compile exec:java
```

### Run Tests

```bash
mvn test
```

## Usage Examples

### 1. Using PooledExecutorService (Recommended)

```java
// Create service with coordinated pool and executor
PooledExecutorService<MyCorbaClient, String> service = new PooledExecutorService<>(
    () -> new MyCorbaClient("corba://server:1234/service"),
    ResourcePoolConfig.builder()
        .maxPoolSize(10)
        .build(),
    ExecutorConfig.builder()
        .concurrency(10)
        .queueCapacity(5000)
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

Async variants are also available: `executeAsync()` and `executeAllAsync()` return `CompletableFuture` results, and `executeAll(operations, timeout)` enforces a batch timeout.

### 2. Using Components Separately

```java
// Create resource pool
ResourcePool<MyCorbaClient> pool = new ResourcePool<>(
    () -> new MyCorbaClient("corba://server:1234"),
    ResourcePoolConfig.builder()
        .maxPoolSize(10)
        .build()
);

// Create bounded executor
BoundedConcurrencyExecutor executor = new BoundedConcurrencyExecutor(
    ExecutorConfig.builder()
        .concurrency(10)
        .queueCapacity(5000)
        .build()
);

// Submit tasks
List<CompletableFuture<TaskResult<String>>> futures = new ArrayList<>();
for (Request req : requests) {
    futures.add(executor.submit(req.getId(), () -> 
        pool.execute(client -> client.execute(req))
    ));
}

// Wait for results
List<TaskResult<String>> results = executor.awaitAll(futures);
```

If you need a per-borrow timeout at the pool level, use `pool.executeWithTimeout(Duration, operation)`. The executor also supports `submit(Supplier)` for auto-generated task IDs and `submitAll(TaskSubmission)` for batch submissions.

### 3. Implementing Your Own PooledResource

```java
public class MyCorbaClient implements RemoteClient<Request, String> {
    private final String id = UUID.randomUUID().toString();
    private final String endpoint;
    private final ORB orb;
    private final MyService service;
    private boolean connected = true;

    public MyCorbaClient(String endpoint) {
        this.endpoint = endpoint;
        this.orb = ORB.init(args, null);
        this.service = // ... CORBA setup
    }

    @Override
    public String execute(Request request) {
        return service.invoke(request);  // Blocking CORBA call
    }

    @Override
    public boolean isValid() {
        return connected && orb != null;
    }

    @Override
    public String getEndpoint() {
        return endpoint;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void reset() {
        // Reset state for reuse
    }

    @Override
    public void close() {
        connected = false;
        orb.shutdown(true);
    }

    @Override
    public String getId() {
        return id;
    }
}
```

## Spring Integration

```java
@Configuration
public class ConcurrencyConfig {

    @Bean(destroyMethod = "close")
    public PooledExecutorService<MyCorbaClient, String> corbaService(
            @Value("${corba.pool.size:10}") int poolSize,
            @Value("${corba.concurrency:10}") int concurrency) {
        
        return new PooledExecutorService<>(
            () -> new MyCorbaClient(corbaEndpoint),
            ResourcePoolConfig.builder()
                .maxPoolSize(poolSize)
                .build(),
            ExecutorConfig.builder()
                .concurrency(concurrency)
                .queueCapacity(5000)
                .threadNamePrefix("corba-worker")
                .build()
        );
    }
}

@Service
public class MyService {
    
    @Autowired
    private PooledExecutorService<MyCorbaClient, String> corbaService;

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

## JBoss/WildFly Integration

```java
@Singleton
@Startup
public class CorbaServiceManager {

    private PooledExecutorService<MyCorbaClient, String> service;

    @PostConstruct
    public void init() {
        service = new PooledExecutorService<>(
            () -> new MyCorbaClient(endpoint),
            10,   // pool size
            5000  // queue capacity
        );
    }

    @PreDestroy
    public void cleanup() {
        if (service != null) {
            service.close();
        }
    }

    public PooledExecutorService<MyCorbaClient, String> getService() {
        return service;
    }
}
```

## Configuration Options

### ResourcePoolConfig

| Option | Default | Description | Meaning & Effect |
|--------|---------|-------------|-----------------|
| `minPoolSize` | 2 | Minimum idle resources | Number of resources kept alive at all times. Higher values reduce cold-start latency at the cost of holding idle resources. Set to at least 1 in production. |
| `maxPoolSize` | 10 | Maximum resources (controls parallelism) | Hard cap on total live resources (e.g. CORBA connections). Also acts as an implicit concurrency ceiling — no more than this many tasks can hold a resource simultaneously. Must not exceed the downstream server's connection limit. |
| `maxWaitTime` | 30s | Time to wait for a resource | How long a caller blocks when the pool is exhausted. If exceeded, a `ResourcePoolException` is thrown. Too short causes spurious failures under burst load; too long allows caller threads to pile up. |
| `maxIdleTime` | 5min | Idle time before eviction | A resource idle longer than this is evicted and closed. Prevents stale or leaked connections. Lower = more aggressive cleanup; higher = connections stay warm longer. |
| `testOnBorrow` | true | Validate before use | Calls `isValid()` before handing a resource to a caller. Catches broken connections before they fail an operation. Adds a small per-borrow latency cost. Recommended `true` on unreliable networks. |
| `testOnReturn` | false | Validate before returning to the pool | Calls `isValid()` when a resource is returned. Provides an extra safety net but doubles validation calls. Usually redundant when `testOnBorrow` is `true`. |
| `testWhileIdle` | true | Validate idle resources periodically | Background thread validates idle resources on each eviction sweep. Keeps the pool healthy during quiet periods without impacting hot-path latency. |
| `timeBetweenEvictionRuns` | 30s | How often to run idle eviction | Interval between background eviction/validation sweeps. Shorter = stale resources detected faster; longer = less background overhead. Works in tandem with `testWhileIdle` and `maxIdleTime`. |
| `blockWhenExhausted` | true | Block vs fail when exhausted | When `true`, callers block up to `maxWaitTime` (backpressure). When `false`, throws immediately. Use `true` for batch workloads where losing work is unacceptable; `false` for latency-sensitive APIs where failing fast is preferable. |

#### ResourcePoolConfig Combination Examples

**High-throughput batch** — absorb bursts, keep connections warm, never fail immediately:
```java
ResourcePoolConfig.builder()
    .minPoolSize(5)
    .maxPoolSize(20)
    .maxWaitTime(Duration.ofMinutes(2))
    .blockWhenExhausted(true)
    .testOnBorrow(true)
    .testWhileIdle(true)
    .timeBetweenEvictionRuns(Duration.ofSeconds(30))
    .build()
```

**Low-latency API** — small pool, fail fast when exhausted, validate on borrow:
```java
ResourcePoolConfig.builder()
    .maxPoolSize(10)
    .maxWaitTime(Duration.ofSeconds(2))
    .blockWhenExhausted(false)   // throw immediately when pool is full
    .testOnBorrow(true)
    .build()
```

**Testing / local dev** — minimal footprint, skip validation overhead:
```java
ResourcePoolConfig.builder()
    .minPoolSize(1)
    .maxPoolSize(3)
    .maxWaitTime(Duration.ofSeconds(5))
    .testOnBorrow(false)         // skip validation for faster tests
    .testWhileIdle(false)
    .build()
```

---

### ExecutorConfig

| Option | Default | Description | Meaning & Effect |
|--------|---------|-------------|-----------------|
| `concurrency` | 10 | Max parallel task executions | Semaphore permit count — the true parallelism ceiling. No more than this many tasks execute simultaneously, regardless of thread pool size. Should match `ResourcePoolConfig.maxPoolSize` so a task holding a permit is always guaranteed a pool resource. |
| `corePoolSize` | 10 | Minimum executor threads (defaults to concurrency) | Threads kept alive even when idle. Setting equal to `concurrency` prevents thread-creation latency on bursts. Lower = fewer idle threads but slower ramp-up. |
| `maxPoolSize` | 10 | Maximum executor threads (defaults to concurrency) | Maximum platform threads. Since the semaphore is the actual concurrency limit, raising this above `concurrency` adds threads without increasing parallelism. Keep equal to `concurrency` in most cases. |
| `queueCapacity` | 1000 | Max pending tasks | Maximum tasks waiting for a semaphore permit. When full, `rejectionPolicy` fires. Too small = premature rejections during bursts; too large = unbounded memory growth under sustained overload. |
| `taskTimeout` | 5min | Max time per task | Hard per-task deadline. Task is interrupted when exceeded. Prevents runaway or stuck tasks from holding semaphore permits indefinitely, which would starve new work. |
| `rejectionPolicy` | BLOCK | BLOCK, REJECT, or CALLER_RUNS | Behavior when the queue is full. `BLOCK` — caller blocks until space opens (best for batch; preserves all work). `REJECT` — throws `RejectedExecutionException` immediately (best for APIs; fail-fast). `CALLER_RUNS` — the submitting thread runs the task inline (simple backpressure, but ties up the caller). |
| `threadNamePrefix` | "bounded-executor" | Thread naming for debugging | Prefix for worker thread names in thread dumps and logs. Use a descriptive name like `"corba-worker"` to identify this executor's threads during production debugging. |
| `daemon` | false | Daemon threads (for testing) | When `false`, the JVM waits for in-flight tasks before exiting (safe for production). When `true`, the JVM exits without waiting — useful in tests to avoid hanging the build, but risky in production. |

> **Tip:** Set `concurrency` equal to `ResourcePoolConfig.maxPoolSize`. A task that acquires a semaphore permit is then guaranteed a pool resource immediately, eliminating pool-wait latency and simplifying capacity planning.

#### ExecutorConfig Combination Examples

**High-throughput batch — absorb large queues, block instead of dropping work:**
```java
ExecutorConfig.builder()
    .concurrency(10)                         // matches ResourcePool.maxPoolSize(10)
    .queueCapacity(5000)                     // absorb burst without rejection
    .rejectionPolicy(RejectionPolicy.BLOCK)  // backpressure to caller
    .taskTimeout(Duration.ofMinutes(3))
    .threadNamePrefix("corba-worker")
    .build()
```

**Latency-sensitive API — fail fast under overload:**
```java
ExecutorConfig.builder()
    .concurrency(20)
    .queueCapacity(200)                       // small queue → reject quickly when overloaded
    .rejectionPolicy(RejectionPolicy.REJECT)  // caller handles the exception
    .taskTimeout(Duration.ofSeconds(30))
    .threadNamePrefix("api-worker")
    .build()
```

**Testing — small pool, daemon threads so the JVM exits cleanly:**
```java
ExecutorConfig.builder()
    .concurrency(5)
    .queueCapacity(100)
    .daemon(true)                             // don't block JVM exit after tests
    .taskTimeout(Duration.ofSeconds(10))
    .build()
```

## Thread Starvation Prevention

This solution prevents thread starvation through several mechanisms:

1. **Dedicated Thread Pool**: The executor uses its own threads, separate from application thread pools (e.g., Tomcat, JBoss)

2. **Semaphore-Based Limiting**: Even if more threads exist, the semaphore ensures only N operations run concurrently

3. **Bounded Queue with Backpressure**: When the queue is full, the BLOCK policy prevents overwhelming the system

4. **Resource Pool Integration**: The pool ensures expensive resources are reused efficiently

## Requirements

- Java 17 (configured in `pom.xml`)
- Maven 3.6+

## Dependencies

- Apache Commons Pool2 (for resource pooling)
- SLF4J (API + Simple runtime logger)
- JUnit 5 (for testing)

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
│   ├── BoundedConcurrencyExecutor.java  # Bounded parallelism executor
│   ├── ExecutorConfig.java              # Executor configuration
│   └── TaskResult.java                  # Task result wrapper
├── client/
│   ├── RemoteClient.java            # Generic remote client interface
│   ├── RemoteClientException.java   # Client exceptions
│   └── SimulatedCorbaClient.java    # Test implementation
├── service/
│   └── PooledExecutorService.java   # Combined pool + executor
└── demo/
    └── ConcurrentCorbaDemo.java     # Demonstration
```

## License

MIT
