package hle.org.workqueue.demo;

import hle.org.workqueue.engine.CallResult;
import hle.org.workqueue.engine.ExternalService;
import hle.org.workqueue.engine.IdempotencyKey;
import java.time.Duration;

/** Replace this method with your job logic. The sample has no external side effects. */
public final class ExampleHandler implements ExternalService {
    @Override
    public CallResult call(IdempotencyKey key, long claimToken, String payload, Duration timeout) {
        // A real downstream must durably deduplicate key.value() across retries and honor timeout.
        // Results must fit in 1000 UTF-8 bytes; these short ASCII sample payloads do.
        return new CallResult("processed:" + payload);
    }
}
