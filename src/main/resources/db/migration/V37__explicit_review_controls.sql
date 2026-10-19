-- Append-only owner decisions; original evidence, opinions and provider costs remain immutable.
ALTER TABLE committee_ai_job DROP CONSTRAINT committee_ai_job_state_check;
ALTER TABLE committee_ai_job ADD CONSTRAINT committee_ai_job_state_check
 CHECK(state IN ('QUEUED','RUNNING','FINALIZING','COMPLETE','FAILED','UNKNOWN','BUDGET_WAIT','STALE','PAUSED','CLOSED'));
ALTER TABLE committee_ai_job ADD COLUMN restart_of_job_id BIGINT UNIQUE REFERENCES committee_ai_job(id);
ALTER TABLE committee_ai_job DROP CONSTRAINT committee_ai_job_scope_id_pack_hash_prompt_version_key;
CREATE UNIQUE INDEX committee_original_pack_unique ON committee_ai_job(scope_id,pack_hash,prompt_version)
 WHERE restart_of_job_id IS NULL;

CREATE TABLE committee_job_action (
 id BIGSERIAL PRIMARY KEY,
 job_id BIGINT NOT NULL REFERENCES committee_ai_job(id),
 action VARCHAR(16) NOT NULL CHECK(action IN ('PAUSE','RESUME','RESTART','CLOSE')),
 client_key UUID NOT NULL UNIQUE,
 requested_at TIMESTAMPTZ NOT NULL,
 target_job_id BIGINT REFERENCES committee_ai_job(id),
 unknown_cost_acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
 CHECK((action='RESTART')=(target_job_id IS NOT NULL)),
 CHECK(NOT unknown_cost_acknowledged OR action='RESTART')
);
CREATE INDEX committee_job_action_recent ON committee_job_action(job_id,requested_at DESC);
CREATE TRIGGER committee_job_action_immutable BEFORE UPDATE OR DELETE ON committee_job_action
 FOR EACH ROW EXECUTE FUNCTION preserve_committee_record();

CREATE OR REPLACE FUNCTION protect_committee_ai_job_pack() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'AI input packs are immutable'; END IF;
 IF NEW.scope_id<>OLD.scope_id OR NEW.brief_id<>OLD.brief_id OR NEW.pack_hash<>OLD.pack_hash
 OR NEW.prompt_version<>OLD.prompt_version OR NEW.pack<>OLD.pack OR NEW.meeting_day<>OLD.meeting_day
 OR NEW.auto_slot IS DISTINCT FROM OLD.auto_slot OR NEW.created_at<>OLD.created_at
 OR NEW.restart_of_job_id IS DISTINCT FROM OLD.restart_of_job_id THEN
  RAISE EXCEPTION 'AI input packs are immutable';
 END IF;
 RETURN NEW;
END;
$$;
