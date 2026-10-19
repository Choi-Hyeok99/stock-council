CREATE TABLE discord_report_target (
    target_key VARCHAR(64) PRIMARY KEY,
    last_report_id BIGINT NOT NULL DEFAULT 0,
    last_meeting_hash VARCHAR(64),
    last_meeting_at TIMESTAMPTZ,
    disabled_reason TEXT,
    connected_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE discord_report_delivery (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target_key VARCHAR(64) NOT NULL REFERENCES discord_report_target(target_key),
    source_key VARCHAR(160) NOT NULL,
    author VARCHAR(80) NOT NULL,
    content TEXT NOT NULL CHECK (char_length(content) BETWEEN 1 AND 1900),
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('CONNECTION','DAILY_REPORT','BOT_PROGRESS')),
    state VARCHAR(16) NOT NULL DEFAULT 'QUEUED'
        CHECK (state IN ('QUEUED','SENDING','SENT','UNKNOWN','FAILED','EXPIRED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    message_id VARCHAR(32),
    issue TEXT,
    UNIQUE(target_key, source_key)
);
CREATE INDEX discord_report_ready ON discord_report_delivery(target_key, state, id);
