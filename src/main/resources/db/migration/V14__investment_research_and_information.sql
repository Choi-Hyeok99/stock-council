CREATE TABLE public_information (
    id VARCHAR(64) PRIMARY KEY,
    source VARCHAR(80) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    title VARCHAR(500) NOT NULL,
    url TEXT NOT NULL,
    published_at TIMESTAMPTZ,
    published_date DATE,
    discovered_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE information_company (
    information_id VARCHAR(64) NOT NULL REFERENCES public_information(id),
    stock_code VARCHAR(6) NOT NULL,
    match_basis VARCHAR(30) NOT NULL,
    PRIMARY KEY(information_id,stock_code)
);
CREATE INDEX information_recent ON public_information(discovered_at DESC);
CREATE INDEX information_company_stock ON information_company(stock_code);
CREATE TABLE information_source_status (
    source VARCHAR(80) PRIMARY KEY,
    state VARCHAR(20) NOT NULL,
    last_attempt_at TIMESTAMPTZ NOT NULL,
    last_success_at TIMESTAMPTZ,
    item_count INTEGER NOT NULL DEFAULT 0,
    coverage TEXT NOT NULL,
    issue TEXT
);
CREATE TABLE investment_idea_snapshot (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    generated_at TIMESTAMPTZ NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    report JSONB NOT NULL
);
CREATE TABLE investment_idea_change (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    stock_code VARCHAR(6) NOT NULL,
    stock_name VARCHAR(100) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    before_state VARCHAR(30),
    after_state VARCHAR(30),
    reason TEXT NOT NULL
);
CREATE TABLE discord_idea_cursor (
    target_key VARCHAR(64) PRIMARY KEY REFERENCES discord_report_target(target_key),
    last_snapshot_id BIGINT NOT NULL DEFAULT 0,
    last_fingerprint VARCHAR(64),
    last_sent_at TIMESTAMPTZ
);
ALTER TABLE discord_report_delivery DROP CONSTRAINT discord_report_delivery_kind_check;
ALTER TABLE discord_report_delivery ADD CONSTRAINT discord_report_delivery_kind_check
    CHECK (kind IN ('CONNECTION','DAILY_REPORT','BOT_PROGRESS','IDEA_REPORT'));
