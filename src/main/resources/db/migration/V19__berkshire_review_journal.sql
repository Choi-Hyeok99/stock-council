CREATE TABLE berkshire_review_snapshot (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scope_id VARCHAR(64) REFERENCES execution_account(scope_id),
    generated_at TIMESTAMPTZ NOT NULL,
    version VARCHAR(80) NOT NULL,
    curriculum_hash VARCHAR(64) NOT NULL,
    report JSONB NOT NULL
);
CREATE INDEX berkshire_review_history ON berkshire_review_snapshot(scope_id,id DESC);
CREATE FUNCTION protect_berkshire_review() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Berkshire review snapshots are append only';
END;
$$;
CREATE TRIGGER berkshire_review_immutable BEFORE UPDATE OR DELETE ON berkshire_review_snapshot
FOR EACH ROW EXECUTE FUNCTION protect_berkshire_review();
