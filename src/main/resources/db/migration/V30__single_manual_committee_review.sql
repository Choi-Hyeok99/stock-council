-- Local owner authorization for one specific extra review on a Seoul date.
-- No web endpoint grants this exception; normal automatic scheduling stays at its base limit.
CREATE TABLE committee_extra_review_authorization (
 scope_id VARCHAR(128) NOT NULL,
 meeting_day DATE NOT NULL,
 brief_id BIGINT NOT NULL UNIQUE REFERENCES committee_brief(id),
 authorized_at TIMESTAMPTZ NOT NULL,
 reason VARCHAR(240) NOT NULL,
 PRIMARY KEY(scope_id,meeting_day)
);
CREATE TRIGGER committee_extra_review_immutable BEFORE UPDATE OR DELETE ON committee_extra_review_authorization
 FOR EACH ROW EXECUTE FUNCTION preserve_committee_record();
