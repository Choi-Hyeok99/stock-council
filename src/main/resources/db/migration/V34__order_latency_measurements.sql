CREATE TABLE execution_latency_measurement (
 id UUID PRIMARY KEY,
 scope_id VARCHAR(128) NOT NULL,
 operation VARCHAR(32) NOT NULL,
 measured_at TIMESTAMPTZ NOT NULL,
 order_id BIGINT REFERENCES execution_order(id),
 outcome VARCHAR(32) NOT NULL,
 dispatch_ms BIGINT CHECK(dispatch_ms>=0),
 total_ms BIGINT NOT NULL CHECK(total_ms>=0),
 stages JSONB NOT NULL CHECK(jsonb_typeof(stages)='object')
);
CREATE INDEX execution_latency_recent ON execution_latency_measurement(scope_id,measured_at DESC);
CREATE TRIGGER execution_latency_immutable BEFORE UPDATE OR DELETE ON execution_latency_measurement
 FOR EACH ROW EXECUTE FUNCTION preserve_committee_record();
