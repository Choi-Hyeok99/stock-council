CREATE TABLE market_index_sample (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    index_code VARCHAR(4) NOT NULL CHECK (index_code IN ('0001', '1001')),
    attempted_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ,
    business_date DATE,
    basis_at TIMESTAMPTZ,
    index_value NUMERIC(19,6) CHECK (index_value > 0),
    previous_close NUMERIC(19,6),
    basis_kind VARCHAR(30),
    source VARCHAR(50),
    state VARCHAR(30) NOT NULL,
    detail TEXT NOT NULL
);
CREATE INDEX idx_market_index_sample_time ON market_index_sample (index_code, attempted_at DESC);

CREATE TABLE market_index_checkpoint (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    report_date DATE NOT NULL,
    index_code VARCHAR(4) NOT NULL CHECK (index_code IN ('0001', '1001')),
    phase VARCHAR(10) NOT NULL CHECK (phase IN ('OPEN', 'CLOSE')),
    recorded_at TIMESTAMPTZ NOT NULL,
    basis_kind VARCHAR(30) NOT NULL,
    sample_id BIGINT NOT NULL REFERENCES market_index_sample(id),
    UNIQUE (report_date, index_code, phase)
);
