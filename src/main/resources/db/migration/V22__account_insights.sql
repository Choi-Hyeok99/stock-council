-- Account identity remains the existing private salted scope; no raw account IDs.
CREATE TABLE account_valuation (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 scope_id VARCHAR(64) NOT NULL REFERENCES execution_account(scope_id),
 basis_at TIMESTAMPTZ NOT NULL, recorded_at TIMESTAMPTZ NOT NULL,
 assets NUMERIC(19,2) NOT NULL CHECK(assets>0), cash NUMERIC(19,2),
 kospi NUMERIC, kosdaq NUMERIC,
 UNIQUE(scope_id,basis_at)
);
CREATE INDEX account_valuation_period ON account_valuation(scope_id,basis_at);
CREATE TABLE account_cash_flow (
 id UUID PRIMARY KEY, scope_id VARCHAR(64) NOT NULL REFERENCES execution_account(scope_id),
 occurred_at TIMESTAMPTZ NOT NULL, amount NUMERIC(19,2) NOT NULL CHECK(amount<>0),
 recorded_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX account_flow_period ON account_cash_flow(scope_id,occurred_at);
CREATE TABLE account_flow_review (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 scope_id VARCHAR(64) NOT NULL REFERENCES execution_account(scope_id),
 from_at TIMESTAMPTZ NOT NULL, through_at TIMESTAMPTZ NOT NULL, recorded_at TIMESTAMPTZ NOT NULL,
 CHECK(through_at>=from_at)
);
CREATE TABLE order_cost_confirmation (
 id UUID PRIMARY KEY, scope_id VARCHAR(64) NOT NULL REFERENCES execution_account(scope_id),
 order_id BIGINT NOT NULL REFERENCES execution_order(id),
 filled_quantity INTEGER NOT NULL CHECK(filled_quantity>0),
 commission NUMERIC(19,2) NOT NULL CHECK(commission>=0), tax NUMERIC(19,2) NOT NULL CHECK(tax>=0),
 recorded_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX order_cost_confirmation_latest ON order_cost_confirmation(scope_id,order_id,recorded_at DESC);
CREATE TRIGGER account_valuation_immutable BEFORE UPDATE OR DELETE ON account_valuation FOR EACH ROW EXECUTE FUNCTION protect_financial_evidence();
CREATE TRIGGER account_flow_immutable BEFORE UPDATE OR DELETE ON account_cash_flow FOR EACH ROW EXECUTE FUNCTION protect_financial_evidence();
CREATE TRIGGER account_flow_review_immutable BEFORE UPDATE OR DELETE ON account_flow_review FOR EACH ROW EXECUTE FUNCTION protect_financial_evidence();
CREATE TRIGGER order_cost_confirmation_immutable BEFORE UPDATE OR DELETE ON order_cost_confirmation FOR EACH ROW EXECUTE FUNCTION protect_financial_evidence();

ALTER TABLE strategy_observation_outcome ADD COLUMN elapsed_trading_days INTEGER;
ALTER TABLE strategy_observation_outcome ADD COLUMN same_time_window BOOLEAN;
ALTER TABLE execution_order ADD COLUMN strategy_code VARCHAR(64) NOT NULL DEFAULT 'LEGACY_UNCLASSIFIED';
ALTER TABLE execution_order ADD COLUMN strategy_policy VARCHAR(128);
CREATE TABLE disclosure_impact_evidence (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 stock_code VARCHAR(6) NOT NULL, information_id VARCHAR(64) NOT NULL,
 receipt VARCHAR(14) NOT NULL, fetched_at TIMESTAMPTZ NOT NULL,
 state VARCHAR(32) NOT NULL, payload JSONB NOT NULL, content_hash VARCHAR(64) NOT NULL
);
CREATE INDEX disclosure_impact_latest ON disclosure_impact_evidence(stock_code,information_id,id DESC);
CREATE TRIGGER disclosure_impact_immutable BEFORE UPDATE OR DELETE ON disclosure_impact_evidence FOR EACH ROW EXECUTE FUNCTION protect_financial_evidence();
CREATE FUNCTION protect_execution_strategy() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.strategy_code IS DISTINCT FROM OLD.strategy_code OR NEW.strategy_policy IS DISTINCT FROM OLD.strategy_policy THEN RAISE EXCEPTION 'order strategy is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER execution_strategy_immutable BEFORE UPDATE ON execution_order FOR EACH ROW EXECUTE FUNCTION protect_execution_strategy();
CREATE TABLE discord_account_cursor (
 target_key VARCHAR(64) NOT NULL REFERENCES discord_report_target(target_key),
 scope_id VARCHAR(64) NOT NULL REFERENCES execution_account(scope_id),
 last_fingerprint VARCHAR(64), last_queued_at TIMESTAMPTZ,
 PRIMARY KEY(target_key,scope_id)
);
