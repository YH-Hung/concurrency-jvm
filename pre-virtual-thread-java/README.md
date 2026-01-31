# pre-virtual-thread-java

A concurrent request processing framework that demonstrates handling thousands of blocking I/O operations using traditional platform threads with controlled concurrency and resource pooling.

## Overview

This project showcases pre-Java 21 concurrency patterns for handling high-throughput workloads that involve blocking I/O operations (database calls, external APIs, etc.). It uses:

- **Generic Resource Pool**: Abstract pool interface with Apache Commons Pool 2 implementation
- **Injectable Request Logic**: Functional interfaces for flexible task execution
- **Backpressure Control**: ThreadPoolExecutor with bounded queue and CallerRunsPolicy
- **Multiple Processing Modes**: Batch processing, streaming, and callback-based patterns

## Architecture

### Key Components

- **`ResourcePool<T>`**: Interface for pooled resource management
- **`Acp2ResourcePool<T>`**: Apache Commons Pool 2 implementation (hides pool library details)
- **`RequestRunner<T>`**: Generic concurrent task executor with three modes:
  - `run()`: Execute N identical void tasks for benchmarking
  - `processAll()`: Process a list with return values (maintains order)
  - `processWithCallback()`: Memory-efficient streaming with callbacks
- **`ResourceTask<T>`**: Functional interface for void tasks
- **`ResourceFunction<T, R>`**: Functional interface for tasks with return values

### Design Principles

1. **Dependency Injection**: Request logic is injected as functional interfaces, not hardcoded
2. **Abstraction**: Commons Pool 2 is an implementation detail, not exposed to clients
3. **Generics**: Framework works with any resource type, not just `SimulatedResource`
4. **Flexibility**: Supports both void tasks (benchmarking) and tasks with results (data processing)

## Requirements

- **Java 17+**
- **Maven 3.8+**

## Building

```bash
cd pre-virtual-thread-java
mvn clean compile
```

## Running

### Basic Usage

From the project directory:

```bash
mvn exec:java -Dexec.mainClass=hle.org.App
```

### With Custom Options

```bash
mvn exec:java \
  -Dexec.mainClass=hle.org.App \
  -Dexec.args="--requests 10000 --concurrency 200 --pool-size 80 --queue-size 2000 --sleep-ms 25 --cpu-iterations 1500"
```

### From Repository Root

```bash
mvn -q -f pre-virtual-thread-java/pom.xml exec:java -Dexec.mainClass=hle.org.App
```

## Configuration Options

| Option | Description | Default |
|--------|-------------|---------|
| `--requests` | Total number of requests to execute | 5000 |
| `--concurrency` | Max concurrent in-flight requests (thread pool size) | 100 |
| `--pool-size` | Max resources in the resource pool | 50 |
| `--queue-size` | Executor queue size before backpressure kicks in | 1000 |
| `--sleep-ms` | Blocking sleep per request (simulates I/O) | 20 |
| `--cpu-iterations` | CPU work iterations per request (simulates computation) | 1000 |
| `--min-idle` | Min idle resources maintained in pool | min(5, pool-size) |
| `--max-idle` | Max idle resources allowed in pool | pool-size |
| `--help` | Show usage information | - |

## Sample Output

```
=== Simulation Summary ===
requests=10000, concurrency=200, poolSize=80, queueSize=2000
sleepMs=25, cpuIterations=1500, minIdle=5, maxIdle=80
totalTime=6.48s, throughput=1542.31 req/s, failures=0
latencyMs: avg=129.55, p50=126.21, p95=191.48
pool: active=0, idle=80
```

## Metrics Explained

- **throughput**: Requests per second (total requests / wall-clock time)
- **totalTime**: End-to-end execution time for all requests
- **failures**: Number of requests that threw exceptions
- **latencyMs**: Per-request time from submission to completion (includes queueing and execution)
  - **avg**: Mean latency across all requests
  - **p50**: Median latency (50th percentile)
  - **p95**: 95th percentile latency (tail latency)
- **pool**: Resource pool usage statistics after run completion
  - **active**: Resources currently borrowed (should be 0 after completion)
  - **idle**: Resources available in the pool

## Use Cases

### 1. Benchmarking (Current Default)

Execute N identical tasks to measure throughput and latency:

```java
RequestRunner<SimulatedResource> runner = new RequestRunner<>(pool);
RunResult result = runner.run(5000, 100, 1000, 
    resource -> resource.perform(20, 1000));
```

### 2. Batch Processing with Results

Process a list of items and collect results in order:

```java
List<String> urls = List.of("url1", "url2", "url3", ...);
List<Response> responses = runner.processAll(urls, 100, 1000,
    (httpClient, url) -> httpClient.fetch(url));
```

### 3. Streaming with Callbacks

Memory-efficient processing of large datasets:

```java
runner.processWithCallback(largeDataset, 100,
    (dbConnection, item) -> dbConnection.process(item),
    (item, result) -> System.out.println("Success: " + result),
    (item, error) -> System.err.println("Failed: " + error));
```

## Why This Pattern?

Before Java 21's Virtual Threads, handling thousands of concurrent blocking operations required careful management of:

1. **Thread Pool Sizing**: Too few threads = underutilization; too many = resource exhaustion
2. **Resource Pooling**: Database connections, HTTP clients, etc. are expensive to create
3. **Backpressure**: Unbounded queues can cause OOM; bounded queues with CallerRunsPolicy provide natural flow control
4. **Latency vs Throughput**: Queue depth and concurrency directly impact both metrics

This project demonstrates these patterns with a clean, generic, testable architecture.

## Comparison to Virtual Threads

With Java 21+ Virtual Threads, many of these patterns become simpler:

- **No Thread Pool Management**: Create millions of virtual threads cheaply
- **Simplified Code**: Structured concurrency patterns replace executor services
- **Same Resource Pooling**: Still need to pool expensive resources like DB connections

However, understanding these traditional patterns remains valuable for:
- Working with Java 17 and earlier
- Understanding the historical context and evolution of Java concurrency
- Recognizing when these patterns appear in legacy codebases
