CREATE TABLE kis_paper_balance_sample (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    state VARCHAR(30) NOT NULL,
    detail TEXT NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ,
    total_assets NUMERIC(19, 2),
    deposit NUMERIC(19, 2),
    stock_value NUMERIC(19, 2),
    unrealized_pnl NUMERIC(19, 2),
    source VARCHAR(60) NOT NULL DEFAULT 'KIS_VTS_INQUIRE_BALANCE'
);

CREATE TABLE bot_meeting (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    trigger_kind VARCHAR(30) NOT NULL,
    rule_version VARCHAR(80) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    conclusion VARCHAR(40) NOT NULL,
    summary TEXT NOT NULL,
    report JSONB NOT NULL
);
CREATE INDEX idx_bot_meeting_time ON bot_meeting (occurred_at DESC, id DESC);
