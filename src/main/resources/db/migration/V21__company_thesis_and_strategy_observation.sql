CREATE TABLE company_public_source (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 stock_code VARCHAR(6) NOT NULL,
 source_kind VARCHAR(16) NOT NULL CHECK(source_kind IN ('PROFILE','DOCUMENT','SHARES')),
 source_identity VARCHAR(100) NOT NULL,
 fetched_at TIMESTAMPTZ NOT NULL,
 state VARCHAR(24) NOT NULL,
 payload JSONB NOT NULL,
 content_hash VARCHAR(64) NOT NULL
);
CREATE INDEX company_source_latest ON company_public_source(stock_code,source_kind,source_identity,id DESC);
CREATE TABLE company_thesis_snapshot (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 scope_id VARCHAR(64) REFERENCES execution_account(scope_id),
 generated_at TIMESTAMPTZ NOT NULL,
 version VARCHAR(80) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL,
 report JSONB NOT NULL
);
CREATE INDEX company_thesis_scope ON company_thesis_snapshot(scope_id,id DESC);
CREATE TABLE strategy_paired_observation (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 scope_id VARCHAR(64) NOT NULL REFERENCES execution_account(scope_id),
 thesis_id BIGINT NOT NULL REFERENCES company_thesis_snapshot(id),
 stock_code VARCHAR(6) NOT NULL,
 observed_at TIMESTAMPTZ NOT NULL,
 trade_at TIMESTAMPTZ NOT NULL,
 initial_price NUMERIC NOT NULL CHECK(initial_price>0),
 chart_action VARCHAR(30) NOT NULL,
 value_action VARCHAR(30) NOT NULL,
 commission_rate NUMERIC NOT NULL,
 tax_rate NUMERIC NOT NULL,
 slippage_bps NUMERIC NOT NULL,
 origin JSONB NOT NULL,
 UNIQUE(scope_id,stock_code,trade_at)
);
CREATE TABLE strategy_observation_outcome (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 observation_id BIGINT NOT NULL REFERENCES strategy_paired_observation(id),
 checkpoint VARCHAR(12) NOT NULL CHECK(checkpoint IN ('D1','D5','D20')),
 observed_at TIMESTAMPTZ NOT NULL,
 trade_at TIMESTAMPTZ NOT NULL,
 price NUMERIC NOT NULL CHECK(price>0),
 reference_net_return NUMERIC NOT NULL,
 UNIQUE(observation_id,checkpoint)
);
CREATE TRIGGER company_source_immutable BEFORE UPDATE OR DELETE ON company_public_source FOR EACH ROW EXECUTE FUNCTION protect_financial_evidence();
CREATE TRIGGER thesis_immutable BEFORE UPDATE OR DELETE ON company_thesis_snapshot FOR EACH ROW EXECUTE FUNCTION protect_financial_evidence();
CREATE TRIGGER strategy_observation_immutable BEFORE UPDATE OR DELETE ON strategy_paired_observation FOR EACH ROW EXECUTE FUNCTION protect_financial_evidence();
CREATE TRIGGER strategy_outcome_immutable BEFORE UPDATE OR DELETE ON strategy_observation_outcome FOR EACH ROW EXECUTE FUNCTION protect_financial_evidence();
ALTER TABLE discord_report_delivery DROP CONSTRAINT discord_report_delivery_kind_check;
ALTER TABLE discord_report_delivery ADD CONSTRAINT discord_report_delivery_kind_check CHECK(kind IN ('CONNECTION','DAILY_REPORT','BOT_PROGRESS','IDEA_REPORT','THESIS_REPORT'));
CREATE TABLE discord_thesis_cursor (
 target_key VARCHAR(64) PRIMARY KEY REFERENCES discord_report_target(target_key),
 last_fingerprint VARCHAR(64),last_queued_at TIMESTAMPTZ
);
