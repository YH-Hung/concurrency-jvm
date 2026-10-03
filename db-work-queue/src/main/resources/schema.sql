-- Producers enqueue with: INSERT INTO WORK_ITEM (OPERATION_ID, PAYLOAD) VALUES (?, ?)
-- A duplicate OPERATION_ID fails with SQLSTATE 23505: already enqueued.
CREATE TABLE WORK_ITEM (
    ID           BIGINT        NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    OPERATION_ID VARCHAR(64)   NOT NULL UNIQUE, -- the handler's idempotency key
    PAYLOAD      VARCHAR(1000) NOT NULL,
    STATUS       VARCHAR(7)    NOT NULL DEFAULT 'PENDING' CHECK (STATUS IN ('PENDING', 'CLAIMED', 'DONE', 'FAILED')),
    -- Claimable once passed. While CLAIMED it is the lease end. NULL once DONE or FAILED, so the claim index skips them.
    AVAILABLE_AT TIMESTAMP              DEFAULT CURRENT TIMESTAMP,
    ATTEMPTS     INTEGER       NOT NULL DEFAULT 0, -- +1 per claim, never reset: also the fencing token
    LAST_ERROR   VARCHAR(1000),
    CHECK (AVAILABLE_AT IS NOT NULL OR STATUS IN ('DONE', 'FAILED'))
);

CREATE INDEX WORK_ITEM_AVAILABLE_AT ON WORK_ITEM (AVAILABLE_AT);
