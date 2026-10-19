CREATE TABLE committee_brief (
 id BIGSERIAL PRIMARY KEY,
 scope_id VARCHAR(128) NOT NULL,
 stock_code VARCHAR(6) NOT NULL CHECK(stock_code ~ '^[0-9]{6}$'),
 agenda VARCHAR(20) NOT NULL CHECK(agenda IN ('BUY_REVIEW','SELL_REVIEW')),
 generated_at TIMESTAMPTZ NOT NULL,
 fingerprint VARCHAR(64) NOT NULL,
 report JSONB NOT NULL CHECK(jsonb_typeof(report)='object'),
 UNIQUE(scope_id,stock_code,agenda,fingerprint)
);
CREATE INDEX committee_brief_recent ON committee_brief(scope_id,id DESC);
CREATE TABLE committee_event (
 id BIGSERIAL PRIMARY KEY,
 brief_id BIGINT NOT NULL REFERENCES committee_brief(id),
 occurred_at TIMESTAMPTZ NOT NULL,
 event_type VARCHAR(32) NOT NULL,
 detail VARCHAR(1000) NOT NULL,
 client_key VARCHAR(64),
 UNIQUE(brief_id,client_key)
);
CREATE TABLE committee_review (
 id BIGSERIAL PRIMARY KEY,
 brief_id BIGINT NOT NULL REFERENCES committee_brief(id),
 reviewed_at TIMESTAMPTZ NOT NULL,
 decision VARCHAR(24) NOT NULL CHECK(decision IN ('WATCH','DEFER','REJECT')),
 note VARCHAR(500) NOT NULL,
 client_key VARCHAR(64) NOT NULL,
 UNIQUE(brief_id,client_key)
);
-- Real model outputs only after an API implementation is provided. No fabricated votes inserted.
CREATE TABLE committee_opinion (
 id BIGSERIAL PRIMARY KEY,
 brief_id BIGINT NOT NULL REFERENCES committee_brief(id),
 role_key VARCHAR(32) NOT NULL,
 phase VARCHAR(20) NOT NULL CHECK(phase IN ('INDEPENDENT','FINAL')),
 generated_at TIMESTAMPTZ NOT NULL,
 provider VARCHAR(40) NOT NULL,
 model VARCHAR(100) NOT NULL,
 prompt_version VARCHAR(64) NOT NULL,
 input_fingerprint VARCHAR(64) NOT NULL,
 opinion JSONB NOT NULL CHECK(jsonb_typeof(opinion)='object'),
 input_tokens INTEGER NOT NULL CHECK(input_tokens>=0),
 output_tokens INTEGER NOT NULL CHECK(output_tokens>=0),
 cost_usd NUMERIC(16,8) NOT NULL CHECK(cost_usd>=0),
 UNIQUE(brief_id,role_key,phase)
);
CREATE FUNCTION preserve_committee_record() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Committee records are immutable'; END;
$$;
CREATE TRIGGER committee_brief_immutable BEFORE UPDATE OR DELETE ON committee_brief FOR EACH ROW EXECUTE FUNCTION preserve_committee_record();
CREATE TRIGGER committee_event_immutable BEFORE UPDATE OR DELETE ON committee_event FOR EACH ROW EXECUTE FUNCTION preserve_committee_record();
CREATE TRIGGER committee_review_immutable BEFORE UPDATE OR DELETE ON committee_review FOR EACH ROW EXECUTE FUNCTION preserve_committee_record();
CREATE TRIGGER committee_opinion_immutable BEFORE UPDATE OR DELETE ON committee_opinion FOR EACH ROW EXECUTE FUNCTION preserve_committee_record();
