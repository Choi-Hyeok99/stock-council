-- Paid reviews require a durable explicit owner request. Historical jobs remain unchanged.
CREATE TABLE committee_manual_request (
 job_id BIGINT PRIMARY KEY REFERENCES committee_ai_job(id),
 requested_at TIMESTAMPTZ NOT NULL,
 origin VARCHAR(32) NOT NULL CHECK(origin='OWNER_CONFIRMED')
);
CREATE TRIGGER committee_manual_request_immutable BEFORE UPDATE OR DELETE ON committee_manual_request
 FOR EACH ROW EXECUTE FUNCTION preserve_committee_record();
-- Local-only authorization: permit one specified new brief despite specified old unknown calls.
-- This does not settle, release, retry, or alter the unknown calls or their reserved budget.
CREATE TABLE committee_unknown_review_authorization (
 brief_id BIGINT NOT NULL REFERENCES committee_brief(id),
 unknown_call_id BIGINT NOT NULL REFERENCES committee_ai_call(id),
 authorized_at TIMESTAMPTZ NOT NULL,
 reason VARCHAR(240) NOT NULL,
 PRIMARY KEY(brief_id,unknown_call_id)
);
CREATE TRIGGER committee_unknown_review_immutable BEFORE UPDATE OR DELETE ON committee_unknown_review_authorization
 FOR EACH ROW EXECUTE FUNCTION preserve_committee_record();
