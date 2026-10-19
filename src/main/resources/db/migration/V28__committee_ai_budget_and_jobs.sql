CREATE TABLE committee_ai_job (
 id BIGSERIAL PRIMARY KEY,
 scope_id VARCHAR(128) NOT NULL,
 brief_id BIGINT NOT NULL REFERENCES committee_brief(id),
 pack_hash VARCHAR(64) NOT NULL,
 prompt_version VARCHAR(64) NOT NULL,
 pack JSONB NOT NULL CHECK(jsonb_typeof(pack)='object'),
 meeting_day DATE NOT NULL,
 auto_slot INTEGER,
 state VARCHAR(24) NOT NULL CHECK(state IN ('QUEUED','RUNNING','FINALIZING','COMPLETE','FAILED','UNKNOWN','BUDGET_WAIT','STALE')),
 issue VARCHAR(240),
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 UNIQUE(scope_id,pack_hash,prompt_version),
 UNIQUE(brief_id)
);
CREATE UNIQUE INDEX committee_ai_slot_unique ON committee_ai_job(scope_id,meeting_day,auto_slot) WHERE auto_slot IS NOT NULL;
CREATE INDEX committee_ai_pending ON committee_ai_job(state,id);
CREATE TABLE committee_ai_budget_month (
 month_start DATE PRIMARY KEY,
 committed_usd NUMERIC(16,8) NOT NULL DEFAULT 0 CHECK(committed_usd>=0)
);
CREATE TABLE committee_ai_call (
 id BIGSERIAL PRIMARY KEY,
 job_id BIGINT NOT NULL REFERENCES committee_ai_job(id),
 role_key VARCHAR(32) NOT NULL,
 phase VARCHAR(20) NOT NULL CHECK(phase IN ('INDEPENDENT','FINAL','SUMMARY')),
 model VARCHAR(100) NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 attempt INTEGER NOT NULL DEFAULT 1 CHECK(attempt BETWEEN 1 AND 3),
 month_start DATE NOT NULL REFERENCES committee_ai_budget_month(month_start),
 reserved_usd NUMERIC(16,8) NOT NULL CHECK(reserved_usd>0),
 state VARCHAR(24) NOT NULL CHECK(state IN ('RESERVED','SENT','SETTLED','NOT_SENT','REJECTED','UNKNOWN')),
 input_tokens INTEGER CHECK(input_tokens>=0),
 output_tokens INTEGER CHECK(output_tokens>=0),
 cached_tokens INTEGER CHECK(cached_tokens>=0),
 cache_write_tokens INTEGER CHECK(cache_write_tokens>=0),
 cost_usd NUMERIC(16,8) CHECK(cost_usd>=0),
 outcome VARCHAR(40),
 created_at TIMESTAMPTZ NOT NULL,
 submitted_at TIMESTAMPTZ,
 settled_at TIMESTAMPTZ,
 UNIQUE(job_id,role_key,phase,attempt)
);
CREATE TABLE committee_summary (
 id BIGSERIAL PRIMARY KEY,
 brief_id BIGINT NOT NULL UNIQUE REFERENCES committee_brief(id),
 generated_at TIMESTAMPTZ NOT NULL,
 model VARCHAR(100) NOT NULL,
 input_fingerprint VARCHAR(64) NOT NULL,
 prompt_version VARCHAR(64) NOT NULL,
 summary JSONB NOT NULL CHECK(jsonb_typeof(summary)='object'),
 input_tokens INTEGER NOT NULL CHECK(input_tokens>=0),
 output_tokens INTEGER NOT NULL CHECK(output_tokens>=0),
 cost_usd NUMERIC(16,8) NOT NULL CHECK(cost_usd>=0)
);
CREATE TRIGGER committee_summary_immutable BEFORE UPDATE OR DELETE ON committee_summary FOR EACH ROW EXECUTE FUNCTION preserve_committee_record();
CREATE FUNCTION protect_committee_ai_job_pack() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'AI input packs are immutable'; END IF;
 IF NEW.scope_id<>OLD.scope_id OR NEW.brief_id<>OLD.brief_id OR NEW.pack_hash<>OLD.pack_hash
 OR NEW.prompt_version<>OLD.prompt_version OR NEW.pack<>OLD.pack OR NEW.meeting_day<>OLD.meeting_day
 OR NEW.auto_slot IS DISTINCT FROM OLD.auto_slot OR NEW.created_at<>OLD.created_at THEN
  RAISE EXCEPTION 'AI input packs are immutable';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER committee_ai_job_pack_immutable BEFORE UPDATE OR DELETE ON committee_ai_job FOR EACH ROW EXECUTE FUNCTION protect_committee_ai_job_pack();
CREATE FUNCTION protect_committee_ai_call() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.state IN ('SETTLED','NOT_SENT','REJECTED','UNKNOWN') THEN RAISE EXCEPTION 'AI call history is immutable'; END IF;
 IF NEW.job_id<>OLD.job_id OR NEW.role_key<>OLD.role_key OR NEW.phase<>OLD.phase OR NEW.model<>OLD.model
 OR NEW.request_hash<>OLD.request_hash OR NEW.month_start<>OLD.month_start OR NEW.reserved_usd<>OLD.reserved_usd
 OR NEW.attempt<>OLD.attempt OR NEW.created_at<>OLD.created_at THEN RAISE EXCEPTION 'AI call reservation is immutable'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER committee_ai_call_guard BEFORE UPDATE OR DELETE ON committee_ai_call FOR EACH ROW EXECUTE FUNCTION protect_committee_ai_call();
