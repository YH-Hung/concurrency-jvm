## pre-virtual-thread-java

Demonstrates running thousands of blocking requests with a controlled concurrency limit and a generic resource pool, avoiding thread starvation by applying backpressure.

### Requirements

- Java 17
- Maven 3.8+

### Run

From the repository root:

```
mvn -q -f pre-virtual-thread-java/pom.xml exec:java -Dexec.mainClass=hle.org.App
```

Pass options with `-Dexec.args`:

```
mvn -q -f pre-virtual-thread-java/pom.xml exec:java \
  -Dexec.mainClass=hle.org.App \
  -Dexec.args="--requests 10000 --concurrency 200 --pool-size 80 --queue-size 2000 --sleep-ms 25 --cpu-iterations 1500"
```

### Options

- `--requests <int>`: total number of requests (default: 5000)
- `--concurrency <int>`: max concurrent in-flight requests (default: 100)
- `--pool-size <int>`: max resources in pool (default: 50)
- `--queue-size <int>`: executor queue size before backpressure (default: 1000)
- `--sleep-ms <int>`: blocking sleep per request (default: 20)
- `--cpu-iterations <int>`: CPU work iterations per request (default: 1000)
- `--min-idle <int>`: min idle resources (default: min(5, pool-size))
- `--max-idle <int>`: max idle resources (default: pool-size)
- `--help`: show usage

### Sample output

```
=== Simulation Summary ===
requests=10000, concurrency=200, poolSize=80, queueSize=2000
sleepMs=25, cpuIterations=1500, minIdle=5, maxIdle=80
totalTime=6.48s, throughput=1542.31 req/s, failures=0
latencyMs: avg=129.55, p50=126.21, p95=191.48
pool: active=0, idle=80
```

### Metrics explained

- `throughput`: total requests divided by end-to-end time
- `latencyMs`: per-request time from submission to completion (includes queueing)
- `p50` / `p95`: median and 95th percentile latency
- `pool`: resource usage after the run completes
