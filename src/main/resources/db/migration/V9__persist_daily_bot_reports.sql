CREATE TABLE daily_bot_report (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    report_date DATE NOT NULL,
    slot VARCHAR(20) NOT NULL CHECK (slot IN ('MORNING', 'MIDDAY', 'CLOSE')),
    scheduled_at TIMESTAMPTZ NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    timing VARCHAR(40) NOT NULL CHECK (timing IN ('ON_TIME', 'DELAYED_CURRENT_SNAPSHOT')),
    curriculum_version VARCHAR(80) NOT NULL,
    report JSONB NOT NULL,
    UNIQUE (report_date, slot),
    CHECK (generated_at >= scheduled_at)
);
CREATE INDEX idx_daily_bot_report_generated ON daily_bot_report (generated_at DESC, id DESC);
