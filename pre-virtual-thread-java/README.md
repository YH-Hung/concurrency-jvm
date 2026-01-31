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

| Option | Default | Description |
|--------|---------|-------------|
| `minPoolSize` | 2 | Minimum idle resources |
| `maxPoolSize` | 10 | Maximum resources (controls parallelism) |
| `maxWaitTime` | 30s | Time to wait for a resource |
| `maxIdleTime` | 5min | Idle time before eviction |
| `testOnBorrow` | true | Validate before use |
| `testOnReturn` | false | Validate before returning to the pool |
| `testWhileIdle` | true | Validate idle resources periodically |
| `timeBetweenEvictionRuns` | 30s | How often to run idle eviction |
| `blockWhenExhausted` | true | Block vs fail when exhausted |

### ExecutorConfig

| Option | Default | Description |
|--------|---------|-------------|
| `concurrency` | 10 | Max parallel task executions |
| `corePoolSize` | 10 | Minimum executor threads (defaults to concurrency) |
| `maxPoolSize` | 10 | Maximum executor threads (defaults to concurrency) |
| `queueCapacity` | 1000 | Max pending tasks |
| `taskTimeout` | 5min | Max time per task |
| `rejectionPolicy` | BLOCK | BLOCK, REJECT, or CALLER_RUNS |
| `threadNamePrefix` | "bounded-executor" | Thread naming for debugging |
| `daemon` | false | Daemon threads (for testing) |

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
