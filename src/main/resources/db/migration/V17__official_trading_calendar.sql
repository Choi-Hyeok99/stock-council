CREATE TABLE market_calendar_refresh (
    refresh_date DATE PRIMARY KEY,
    attempted_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL CHECK (attempts BETWEEN 1 AND 3),
    state VARCHAR(16) NOT NULL CHECK (state IN ('IN_PROGRESS','OK','ERROR')),
    issue TEXT
);
CREATE TABLE market_calendar_snapshot (
    id BIGSERIAL PRIMARY KEY,
    basis_month VARCHAR(7) NOT NULL CHECK (basis_month ~ '^[0-9]{4}-[0-9]{2}$'),
    fetched_at TIMESTAMPTZ NOT NULL,
    source TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    days JSONB NOT NULL CHECK (jsonb_typeof(days) = 'array')
);
CREATE INDEX market_calendar_snapshot_latest ON market_calendar_snapshot (fetched_at DESC, id DESC);
