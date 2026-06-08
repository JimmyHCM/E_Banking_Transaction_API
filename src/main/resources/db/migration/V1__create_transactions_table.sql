-- ============================================================
-- V1: initial schema for the transaction read-model projection
-- ============================================================
-- This table is a rebuildable CQRS projection of the Kafka
-- "transactions" topic.  Drop and replay from offset 0 to rebuild.
-- ============================================================

CREATE TABLE IF NOT EXISTS transaction (
    id           VARCHAR(36)     NOT NULL,
    customer_id  VARCHAR(50)     NOT NULL,
    -- Stored as signed NUMERIC so credits are positive, debits negative.
    -- NUMERIC(19,4) matches BigDecimal precision used in the application.
    amount       NUMERIC(19, 4)  NOT NULL,
    currency     CHAR(3)         NOT NULL,
    iban         VARCHAR(34)     NOT NULL,
    value_date   DATE            NOT NULL,
    description  VARCHAR(255)    NOT NULL DEFAULT '',
    ingested_at  TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_transaction PRIMARY KEY (id)
);

-- Primary query pattern:
--   WHERE customer_id = :cid
--     AND value_date BETWEEN :start AND :end
--   ORDER BY value_date DESC
--   LIMIT :size OFFSET :offset
--
-- A composite index on (customer_id, value_date DESC) turns this into
-- an efficient index-range scan regardless of total table size.
CREATE INDEX IF NOT EXISTS idx_transaction_customer_date
    ON transaction (customer_id, value_date DESC);

COMMENT ON TABLE  transaction                IS 'Read-model projection of the Kafka transactions topic.';
COMMENT ON COLUMN transaction.amount         IS 'Signed decimal: positive = credit, negative = debit.';
COMMENT ON COLUMN transaction.ingested_at    IS 'Wall-clock time this row was written by the Kafka projector.';
