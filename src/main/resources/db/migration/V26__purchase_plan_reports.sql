CREATE TABLE purchase_plan_report (
 id BIGSERIAL PRIMARY KEY,
 scope_id VARCHAR(128) NOT NULL,
 generated_at TIMESTAMPTZ NOT NULL,
 plan_date DATE,
 phase VARCHAR(40) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL,
 morning_key VARCHAR(32),
 report JSONB NOT NULL CHECK(jsonb_typeof(report)='object'),
 UNIQUE(scope_id,morning_key)
);
CREATE INDEX purchase_plan_latest ON purchase_plan_report(scope_id,id DESC);
CREATE FUNCTION preserve_purchase_plan_report() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Purchase plan reports are immutable';
END;
$$;
CREATE TRIGGER purchase_plan_immutable BEFORE UPDATE OR DELETE ON purchase_plan_report
 FOR EACH ROW EXECUTE FUNCTION preserve_purchase_plan_report();
