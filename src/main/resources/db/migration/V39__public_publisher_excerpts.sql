-- Bounded text from explicitly public publisher pages; historical meeting evidence is immutable.
CREATE TABLE public_publisher_excerpt (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    information_id VARCHAR(64) NOT NULL REFERENCES public_information(id),
    fetched_at TIMESTAMPTZ NOT NULL,
    state VARCHAR(24) NOT NULL CHECK(state IN ('READY','NO_PUBLIC_TEXT','ERROR')),
    excerpt VARCHAR(1200),
    truncated BOOLEAN NOT NULL DEFAULT FALSE,
    content_hash VARCHAR(64),
    CHECK ((state='READY')=(excerpt IS NOT NULL)),
    CHECK (excerpt IS NOT NULL OR truncated=FALSE)
);
CREATE INDEX publisher_excerpt_latest ON public_publisher_excerpt(information_id,id DESC);
CREATE TRIGGER publisher_excerpt_immutable BEFORE UPDATE OR DELETE ON public_publisher_excerpt
    FOR EACH ROW EXECUTE FUNCTION protect_financial_evidence();
