package hle.org.workqueue.engine;

/** One claim: a row id and the fencing token that claim received (spec §5.2). */
record ClaimKey(long id, long token) {
}
