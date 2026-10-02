package hle.org.workqueue.engine;

/** How one claim's task ended (spec §6 ItemProcessor). */
enum Outcome {
    /** The call returned and the row is DONE with its result. */
    COMPLETED,
    /** The call failed with attempts left: the row is PENDING again after retry-backoff. */
    RETRY_SCHEDULED,
    /** The call failed on the last attempt: the row is FAILED. */
    FAILED,
    /**
     * The claim was no longer this owner's when its outcome was persisted: this persist wrote nothing. An earlier
     * attempt whose acknowledgement was lost may have committed and since been superseded.
     */
    FENCED,
    /**
     * The outcome could not be persisted within completion-retries: the row stays CLAIMED until its lease expires,
     * unless an attempt whose acknowledgement was lost did commit.
     */
    ABANDONED,
    /**
     * The thread was interrupted during the call (it threw InterruptedException, or the interrupt status was set
     * when it returned or threw): any result was discarded, nothing was written, and the row stays CLAIMED until
     * its lease expires.
     */
    INTERRUPTED,
    /** The handle was cancelled before the call: no call was made and nothing was written. */
    CANCELLED
}
