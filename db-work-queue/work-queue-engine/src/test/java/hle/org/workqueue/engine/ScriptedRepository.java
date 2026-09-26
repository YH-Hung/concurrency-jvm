package hle.org.workqueue.engine;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/**
 * A WorkItemRepository for unit tests: complete and retryOrFail answer from a script, in order, and are recorded.
 * It never touches a database; an unscripted persist fails the test with an AssertionError.
 */
class ScriptedRepository extends WorkItemRepository {

    enum Operation { COMPLETE, RETRY_OR_FAIL }

    /** One persist call; {@code value} is the result value of a complete or the error of a retryOrFail. */
    record Write(Operation operation, String owner, ClaimKey claim, String value) {
    }

    private final Deque<Supplier<PersistResult>> script = new ArrayDeque<>();
    private final List<Write> writes = new ArrayList<>();

    ScriptedRepository() {
        super(new DriverManagerDataSource(), DbTimeouts.defaults(),
                new Settings(Duration.ofSeconds(100), 5, Duration.ofSeconds(5)));
    }

    ScriptedRepository thenReturn(PersistResult result) {
        return then(() -> result);
    }

    ScriptedRepository thenThrow(RuntimeException failure) {
        return then(() -> {
            throw failure;
        });
    }

    /** The next persist runs {@code step}. */
    ScriptedRepository then(Supplier<PersistResult> step) {
        script.add(step);
        return this;
    }

    List<Write> writes() {
        return writes;
    }

    @Override
    public PersistResult complete(String owner, ClaimKey claim, String resultValue) {
        return next(new Write(Operation.COMPLETE, owner, claim, resultValue));
    }

    @Override
    public PersistResult retryOrFail(String owner, ClaimKey claim, String error) {
        return next(new Write(Operation.RETRY_OR_FAIL, owner, claim, error));
    }

    private PersistResult next(Write write) {
        writes.add(write);
        Supplier<PersistResult> step = script.poll();
        if (step == null) {
            throw new AssertionError("unscripted " + write.operation());
        }
        return step.get();
    }
}
