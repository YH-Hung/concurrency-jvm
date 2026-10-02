package hle.org.workqueue.engine;

/**
 * Outcome of a fenced persist, after the read-back (spec §6, ItemProcessor step 3). A persist whose
 * earlier attempt committed but lost its acknowledgement reports that attempt's outcome, not FENCED.
 */
enum PersistResult {
    /** The row is DONE with this claim's result. */
    DONE,
    /** The row is PENDING again, claimable after retry-backoff. */
    RETRY_SCHEDULED,
    /** The row is FAILED: attempts are exhausted. */
    FAILED,
    /**
     * The claim is no longer this owner's; this persist wrote nothing. An earlier attempt whose
     * acknowledgement was lost may have committed and since been superseded.
     */
    FENCED
}
