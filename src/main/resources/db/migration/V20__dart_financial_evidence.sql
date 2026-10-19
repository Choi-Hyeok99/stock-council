CREATE TABLE dart_corporation (
    stock_code VARCHAR(6) PRIMARY KEY,
    corp_code VARCHAR(8) NOT NULL UNIQUE,
    corp_name TEXT NOT NULL,
    modified_date DATE NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE dart_financial_fetch (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    stock_code VARCHAR(6) NOT NULL,
    corp_code VARCHAR(8) NOT NULL,
    business_year INTEGER NOT NULL,
    report_code VARCHAR(5) NOT NULL,
    fs_div VARCHAR(3) NOT NULL CHECK(fs_div IN ('CFS','OFS')),
    fetched_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL,
    accounts JSONB NOT NULL,
    content_hash VARCHAR(64) NOT NULL
);
CREATE INDEX dart_financial_lookup ON dart_financial_fetch(stock_code,business_year,report_code,fs_div,id DESC);
CREATE TABLE financial_review_snapshot (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scope_id VARCHAR(64) REFERENCES execution_account(scope_id),
    generated_at TIMESTAMPTZ NOT NULL,
    version VARCHAR(80) NOT NULL,
    report JSONB NOT NULL
);
CREATE INDEX financial_review_history ON financial_review_snapshot(scope_id,id DESC);
CREATE FUNCTION protect_financial_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Financial evidence is append only';
END;
$$;
CREATE TRIGGER financial_fetch_immutable BEFORE UPDATE OR DELETE ON dart_financial_fetch
FOR EACH ROW EXECUTE FUNCTION protect_financial_evidence();
CREATE TRIGGER financial_review_immutable BEFORE UPDATE OR DELETE ON financial_review_snapshot
FOR EACH ROW EXECUTE FUNCTION protect_financial_evidence();
