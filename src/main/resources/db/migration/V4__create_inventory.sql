-- V4: inventory, and the constraints that make overselling impossible.
--
-- MODEL
-- One inventory row per product, sharing the product's primary key. There is
-- exactly one, always, so a separate surrogate key would add nothing.
--
--   available : units that may still be sold
--   reserved  : units held for orders awaiting payment
--
-- Lifecycle of a unit:
--   checkout        available -= n, reserved += n     (reservation)
--   payment success reserved  -= n                    (sold; leaves the system)
--   payment failure reserved  -= n, available += n    (released back)
--   cancellation    reserved  -= n, available += n    (released back)
--
-- CONCURRENCY STRATEGY — the important part
-- Reservation is performed by a single conditional UPDATE:
--
--   UPDATE inventory
--      SET available = available - :qty, reserved = reserved + :qty
--    WHERE product_id = :id AND available >= :qty
--
-- The check and the decrement are one statement, so there is no read-then-write
-- window for two transactions to both observe "enough stock". PostgreSQL takes a
-- row lock for the duration of the UPDATE; a concurrent writer blocks, then
-- re-evaluates `available >= :qty` against the *committed* value. The loser
-- updates zero rows and the service turns that into 409 Conflict.
--
-- The CHECK constraints below are the backstop: even a buggy future query cannot
-- drive either column negative, because the database would reject the write.
--
-- Alternatives considered, and why not:
--   * SELECT ... FOR UPDATE then UPDATE — correct, but two round trips and holds
--     the lock for longer. The conditional UPDATE is strictly better here.
--   * Optimistic @Version with retry — works, but under contention for the last
--     unit it degenerates into a retry storm; every loser must re-read and retry.
--   * Application-level check then UPDATE — the classic bug. Two threads read 1,
--     both decide they can proceed, and stock goes to -1.
-- `version` is still present for ordinary administrative edits, which are
-- low-contention and benefit from lost-update detection.

CREATE TABLE inventory (
    product_id BIGINT      PRIMARY KEY REFERENCES products (id) ON DELETE CASCADE,
    available  INTEGER     NOT NULL DEFAULT 0,
    reserved   INTEGER     NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version    BIGINT      NOT NULL DEFAULT 0,

    -- The guarantees that must hold no matter what any application does.
    CONSTRAINT ck_inventory_available_non_negative CHECK (available >= 0),
    CONSTRAINT ck_inventory_reserved_non_negative  CHECK (reserved  >= 0)
);

-- "Which products are low or out of stock" — an administrative report.
CREATE INDEX idx_inventory_available ON inventory (available);

COMMENT ON TABLE  inventory           IS 'Per-product stock. One row per product, keyed by product_id.';
COMMENT ON COLUMN inventory.available IS 'Sellable units. CHECK (available >= 0) makes overselling impossible at the storage layer.';
COMMENT ON COLUMN inventory.reserved  IS 'Units held for orders awaiting payment. Released on failure or cancellation.';
