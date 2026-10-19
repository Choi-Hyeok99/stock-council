CREATE TABLE execution_account (
    scope_id VARCHAR(64) PRIMARY KEY,
    environment VARCHAR(8) NOT NULL CHECK (environment IN ('PAPER', 'LIVE')),
    automation_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    broker_cash NUMERIC(19,2),
    broker_total_assets NUMERIC(19,2),
    last_reconciled_at TIMESTAMPTZ,
    issue TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE execution_order (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scope_id VARCHAR(64) NOT NULL REFERENCES execution_account(scope_id),
    decision_key VARCHAR(160) NOT NULL,
    stock_code VARCHAR(12) NOT NULL,
    stock_name VARCHAR(100) NOT NULL,
    side VARCHAR(4) NOT NULL CHECK (side IN ('BUY','SELL')),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    limit_price NUMERIC(19,2) NOT NULL CHECK (limit_price > 0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('CREATED','SUBMITTING','SUBMITTED','PARTIALLY_FILLED','FILLED','CANCEL_PENDING','CANCELED','REJECTED','UNKNOWN','EXPIRED')),
    broker_order_id VARCHAR(32),
    organization VARCHAR(32),
    broker_date DATE,
    broker_time TIME,
    filled_quantity INTEGER NOT NULL DEFAULT 0 CHECK (filled_quantity >= 0 AND filled_quantity <= quantity),
    filled_amount NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (filled_amount >= 0),
    commission NUMERIC(19,2) NOT NULL DEFAULT 0,
    tax NUMERIC(19,2) NOT NULL DEFAULT 0,
    commission_rate NUMERIC(16,10) NOT NULL CHECK (commission_rate >= 0),
    tax_rate NUMERIC(16,10) NOT NULL CHECK (tax_rate >= 0),
    entry_order_id BIGINT REFERENCES execution_order(id),
    net_pnl NUMERIC(19,2) NOT NULL DEFAULT 0,
    cancel_order_id VARCHAR(32),
    reason TEXT NOT NULL,
    submitted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(scope_id, decision_key)
);
CREATE UNIQUE INDEX execution_broker_order_unique ON execution_order(scope_id, broker_date, broker_order_id)
    WHERE broker_order_id IS NOT NULL;
-- A single in-flight intent serializes account-wide reservations across processes and sides.
CREATE UNIQUE INDEX execution_one_pending ON execution_order(scope_id)
    WHERE status IN ('CREATED','SUBMITTING','SUBMITTED','PARTIALLY_FILLED','CANCEL_PENDING','UNKNOWN');

CREATE TABLE execution_position (
    scope_id VARCHAR(64) NOT NULL REFERENCES execution_account(scope_id),
    stock_code VARCHAR(12) NOT NULL,
    stock_name VARCHAR(100) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    average_price NUMERIC(19,8) NOT NULL CHECK (average_price > 0),
    entry_cost NUMERIC(19,2) NOT NULL CHECK (entry_cost >= 0),
    entry_order_id BIGINT NOT NULL REFERENCES execution_order(id),
    opened_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(scope_id, stock_code)
);
CREATE TABLE execution_fill (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES execution_order(id),
    cumulative_quantity INTEGER NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    price NUMERIC(19,8) NOT NULL CHECK (price > 0),
    gross_amount NUMERIC(19,2) NOT NULL CHECK (gross_amount > 0),
    commission NUMERIC(19,2) NOT NULL,
    tax NUMERIC(19,2) NOT NULL,
    gross_pnl NUMERIC(19,2) NOT NULL DEFAULT 0,
    net_pnl NUMERIC(19,2) NOT NULL DEFAULT 0,
    cost_basis VARCHAR(60) NOT NULL DEFAULT 'ESTIMATE_BROKER_FILL_NO_EXTRA_SLIPPAGE',
    occurred_at TIMESTAMPTZ NOT NULL,
    UNIQUE(order_id, cumulative_quantity)
);
CREATE TABLE execution_event (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scope_id VARCHAR(64) NOT NULL REFERENCES execution_account(scope_id),
    order_id BIGINT REFERENCES execution_order(id),
    event_type VARCHAR(40) NOT NULL,
    detail TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX execution_event_recent ON execution_event(scope_id, occurred_at DESC);
CREATE INDEX execution_fill_recent ON execution_fill(occurred_at DESC);
