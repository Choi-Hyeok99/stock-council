-- KIS may return the same numeric order number with different leading zeroes.
-- Keep the original spelling for broker requests while enforcing one local owner.
ALTER TABLE execution_order ADD CONSTRAINT execution_numeric_broker_order
    CHECK (broker_order_id IS NULL OR broker_order_id ~ '^[0-9]{1,10}$');

CREATE UNIQUE INDEX execution_broker_order_canonical_unique
    ON execution_order(scope_id, broker_date, ((broker_order_id::NUMERIC)))
    WHERE broker_order_id IS NOT NULL;

-- An explicit owner resume acknowledges past consecutive losses, never today's loss limit.
ALTER TABLE execution_account ADD COLUMN loss_reviewed_at TIMESTAMPTZ;
