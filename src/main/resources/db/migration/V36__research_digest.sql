CREATE TABLE research_digest (
 id BIGSERIAL PRIMARY KEY,
 scope_id VARCHAR(128) NOT NULL,
 report_day DATE NOT NULL,
 slot VARCHAR(16) NOT NULL CHECK(slot IN ('MORNING','MIDDAY','CLOSE')),
 generated_at TIMESTAMPTZ NOT NULL,
 report JSONB NOT NULL,
 UNIQUE(scope_id,report_day,slot)
);
CREATE TRIGGER research_digest_immutable BEFORE UPDATE OR DELETE ON research_digest
 FOR EACH ROW EXECUTE FUNCTION preserve_committee_record();
