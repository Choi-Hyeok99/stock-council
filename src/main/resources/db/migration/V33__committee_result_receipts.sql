-- Read acknowledgements do not alter immutable opinions, summaries, or trading decisions.
CREATE TABLE committee_result_receipt (
 scope_id VARCHAR(128) NOT NULL,
 brief_id BIGINT NOT NULL REFERENCES committee_brief(id),
 first_seen_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(scope_id,brief_id)
);
CREATE TRIGGER committee_result_receipt_immutable BEFORE UPDATE OR DELETE ON committee_result_receipt
 FOR EACH ROW EXECUTE FUNCTION preserve_committee_record();
