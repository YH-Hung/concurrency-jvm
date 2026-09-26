package hle.org.workqueue.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Processes one claimed row (spec §6): at most one external call, then its outcome persisted under the claim's
 * token. It returns an {@link Outcome} and never throws an exception; a JVM {@link Error} propagates to the task
 * body, whose {@code finally} still finishes the handle.
 */
final class ItemProcessor {

    /**
     * @param externalCallTimeout  passed to every call
     * @param completionRetries    persist attempts after the first one fails
     * @param completionRetryDelay the pause before each of those attempts
     */
    record Settings(Duration externalCallTimeout, int completionRetries, Duration completionRetryDelay) {

        Settings {
            Durations.requirePositive("externalCallTimeout", externalCallTimeout);
            if (completionRetries < 0) {
                throw new IllegalArgumentException("completionRetries must not be negative: " + completionRetries);
            }
            Objects.requireNonNull(completionRetryDelay, "completionRetryDelay");
            if (completionRetryDelay.isNegative()) {
                throw new IllegalArgumentException("completionRetryDelay must not be negative: " + completionRetryDelay);
            }
        }

        static Settings from(WorkQueueProperties properties) {
            return new Settings(properties.getExternalCallTimeout(), properties.getCompletionRetries(),
                    properties.getCompletionRetryDelay());
        }
    }

    /** The pause between persist attempts; tests record it instead of sleeping. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    static final String NO_RESULT_ERROR = "the external service returned no result";
    static final String INVALID_OPERATION_ID_ERROR = "OPERATION_ID is not a valid operation identity; not called";

    /** Bounds the logged cause chain, which may be cyclic. */
    private static final int MAX_LOGGED_CAUSES = 8;

    private static final Logger log = LoggerFactory.getLogger(ItemProcessor.class);

    private final WorkItemRepository repository;
    private final ExternalService service;
    private final String owner;
    private final String namespace;
    private final Settings settings;
    private final Sleeper sleeper;

    ItemProcessor(WorkItemRepository repository, ExternalService service, String owner, String namespace,
                  Settings settings) {
        this(repository, service, owner, namespace, settings, Thread::sleep);
    }

    ItemProcessor(WorkItemRepository repository, ExternalService service, String owner, String namespace,
                  Settings settings, Sleeper sleeper) {
        WorkItemRepository.requireOwner(owner);
        IdempotencyKey.requireNamespace(namespace);
        this.repository = Objects.requireNonNull(repository, "repository");
        this.service = Objects.requireNonNull(service, "service");
        this.owner = owner;
        this.namespace = namespace;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /** Processes {@code item}; {@code cancelled} reports whether its handle was cancelled. */
    Outcome process(ClaimedItem item, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            return Outcome.CANCELLED;
        }
        IdempotencyKey key;
        try {
            key = new IdempotencyKey(namespace, item.operationId());
        } catch (IllegalArgumentException e) {
            // Unreachable while CK_WORK_ITEM_OPERATION_ID holds; the attempt fails instead of the task.
            return failed(item, INVALID_OPERATION_ID_ERROR);
        }
        CallResult result;
        try {
            result = service.call(key, item.claimToken(), item.payload(), settings.externalCallTimeout());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.INTERRUPTED;
        } catch (Exception e) {
            return Thread.currentThread().isInterrupted() ? Outcome.INTERRUPTED : failed(item, describe(e));
        }
        if (Thread.currentThread().isInterrupted()) {
            return Outcome.INTERRUPTED;
        }
        if (result == null) {
            return failed(item, NO_RESULT_ERROR);
        }
        return persist(item, () -> repository.complete(owner, item.key(), result.value()));
    }

    private Outcome failed(ClaimedItem item, String error) {
        return persist(item, () -> repository.retryOrFail(owner, item.key(), error));
    }

    // Throwable.toString() calls getLocalizedMessage(), which a downstream exception may override and break.
    private static String describe(Exception failure) {
        try {
            return failure.toString();
        } catch (RuntimeException broken) {
            return failure.getClass().getName();
        }
    }

    // One persist attempt is one DB operation; the repository's read-back turns a retry of a write that did commit
    // into that write's outcome instead of FENCED.
    private Outcome persist(ClaimedItem item, Supplier<PersistResult> write) {
        for (int attempt = 0; ; attempt++) {
            try {
                return outcomeOf(write.get());
            } catch (RuntimeException e) {
                if (attempt == settings.completionRetries() || Thread.currentThread().isInterrupted()) {
                    return abandoned(item, e);
                }
                try {
                    sleeper.sleep(settings.completionRetryDelay());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return abandoned(item, e);
                }
            }
        }
    }

    // Never the throwable itself: its messages may carry keys, payloads or results (spec §5.4), and a logger that
    // reads a message or cause that throws would throw out of process().
    private Outcome abandoned(ClaimedItem item, RuntimeException lastFailure) {
        log.warn("Abandoned row {} token {} of owner {}: its outcome could not be persisted: {}", item.id(),
                item.claimToken(), owner, diagnostics(lastFailure));
        return Outcome.ABANDONED;
    }

    /** The class names down the failure's cause chain, with SQL codes: no messages, and nothing that can throw. */
    private static String diagnostics(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_LOGGED_CAUSES; depth++) {
            text.append(depth == 0 ? "" : ", caused by ").append(current.getClass().getName());
            appendSqlCodes(text, current);
            current = causeOf(current);
        }
        return text.toString();
    }

    private static Throwable causeOf(Throwable failure) {
        try {
            return failure.getCause();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static void appendSqlCodes(StringBuilder text, Throwable failure) {
        if (!(failure instanceof SQLException sql)) {
            return;
        }
        try {
            String state = sql.getSQLState();
            int code = sql.getErrorCode();
            text.append(" (SQLState ").append(state).append(", error code ").append(code).append(')');
        } catch (RuntimeException unreadable) {
            // The class name alone is still a diagnostic.
        }
    }

    private static Outcome outcomeOf(PersistResult result) {
        return switch (result) {
            case DONE -> Outcome.COMPLETED;
            case RETRY_SCHEDULED -> Outcome.RETRY_SCHEDULED;
            case FAILED -> Outcome.FAILED;
            case FENCED -> Outcome.FENCED;
        };
    }
}
