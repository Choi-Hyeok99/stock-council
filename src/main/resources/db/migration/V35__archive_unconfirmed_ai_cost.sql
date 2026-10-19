-- An explicit local owner action may retire an old unknown request from the review inbox.
-- The call and its full budget reservation are preserved. No retry or settlement is implied.
CREATE TABLE committee_archived_call (
 call_id BIGINT PRIMARY KEY REFERENCES committee_ai_call(id),
 archived_at TIMESTAMPTZ NOT NULL,
 reason VARCHAR(240) NOT NULL
);
CREATE TRIGGER committee_archived_call_immutable BEFORE UPDATE OR DELETE ON committee_archived_call
 FOR EACH ROW EXECUTE FUNCTION preserve_committee_record();
