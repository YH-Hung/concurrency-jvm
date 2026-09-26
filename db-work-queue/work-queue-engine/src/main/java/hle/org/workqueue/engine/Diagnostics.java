package hle.org.workqueue.engine;

import java.sql.SQLException;

/**
 * What the engine may log about a failure (spec §5.4): the class names down its cause chain, with SQL codes. Never a
 * message, which may carry keys, payloads or results, and never the throwable itself: a logger that reads a message
 * or cause that throws would throw out of the log call.
 */
final class Diagnostics {

    /** Bounds the cause chain, which may be cyclic. */
    static final int MAX_CAUSES = 8;

    private Diagnostics() {
    }

    /**
     * The class names down {@code failure}'s cause chain, with SQL codes. Never throws: every caller runs it inside a
     * {@code catch}, so anything it let escape, even an {@code Error} from an overridden accessor, would leave that
     * {@code catch} unlogged, reach the thread's default handler (which prints its message), or end a loop.
     */
    static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSES; depth++) {
            text.append(depth == 0 ? "" : ", caused by ").append(current.getClass().getName());
            appendSqlCodes(text, current);
            current = causeOf(current);
        }
        return text.toString();
    }

    private static Throwable causeOf(Throwable failure) {
        try {
            return failure.getCause();
        } catch (Throwable unreadable) {
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
        } catch (Throwable unreadable) {
            // The class name alone is still a diagnostic.
        }
    }
}
