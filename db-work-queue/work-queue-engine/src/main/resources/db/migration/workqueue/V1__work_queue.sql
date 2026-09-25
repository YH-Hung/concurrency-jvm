-- db-work-queue engine schema (spec §4). Applied by the migrate job only; workers never run DDL.
-- Requires the Flyway placeholder workqueueNamespace: this queue's namespace (spec §5.4).

CREATE TABLE WORK_QUEUE_META (
    NAMESPACE VARCHAR(32) NOT NULL,
    CONSTRAINT CK_WORK_QUEUE_META_NAMESPACE CHECK (LENGTH(NAMESPACE) >= 1 AND LOCATE(':', NAMESPACE) = 0)
);

INSERT INTO WORK_QUEUE_META (NAMESPACE) VALUES ('${workqueueNamespace}');

CREATE TABLE WORK_ITEM (
    ID           BIGINT        NOT NULL GENERATED ALWAYS AS IDENTITY,
    OPERATION_ID VARCHAR(65)   NOT NULL,
    PAYLOAD      VARCHAR(1000) NOT NULL,
    STATUS       VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    AVAILABLE_AT TIMESTAMP     NOT NULL DEFAULT CURRENT TIMESTAMP,
    OWNER        VARCHAR(64),
    CLAIM_TOKEN  BIGINT        NOT NULL DEFAULT 0,
    ATTEMPTS     INTEGER       NOT NULL DEFAULT 0,
    RESULT_VALUE VARCHAR(1000),
    LAST_ERROR   VARCHAR(1000),
    CREATED_AT   TIMESTAMP     NOT NULL DEFAULT CURRENT TIMESTAMP,
    UPDATED_AT   TIMESTAMP     NOT NULL DEFAULT CURRENT TIMESTAMP,
    CONSTRAINT PK_WORK_ITEM PRIMARY KEY (ID),
    -- Canonical OPERATION_ID (spec §5.4): 1-64 printable ASCII characters (one byte each), no spaces. The column
    -- (bytes) is one wider than the limit because Db2 silently cuts excess trailing blanks off on assignment: a longer
    -- value ending in blanks arrives here as 65 bytes and fails this CHECK instead of being stored as 64.
    CONSTRAINT CK_WORK_ITEM_OPERATION_ID
        CHECK (LENGTH(OPERATION_ID) BETWEEN 1 AND 64 AND NOT REGEXP_LIKE(OPERATION_ID, '[^!-~]')),
    CONSTRAINT CK_WORK_ITEM_STATUS CHECK (STATUS IN ('PENDING', 'CLAIMED', 'DONE', 'FAILED'))
);

-- Exact uniqueness: Db2 compares strings blank-padded, so on OPERATION_ID alone 'a ' would be a duplicate of 'a'.
-- Db2 checks uniqueness before CK_WORK_ITEM_OPERATION_ID, so the producer would read the unique-key violation as
-- "already enqueued" instead of a malformed id. With the length in the key, only identical ids violate it.
CREATE UNIQUE INDEX UX_WORK_ITEM_OPERATION_ID ON WORK_ITEM (OPERATION_ID, LENGTH(OPERATION_ID));

CREATE INDEX IX_WORK_ITEM_CLAIM ON WORK_ITEM (STATUS, AVAILABLE_AT);
