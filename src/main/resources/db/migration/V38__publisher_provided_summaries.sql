-- Publisher supplied RSS text is separate from its title and never treated as full article verification.
-- Existing immutable committee briefs keep their original evidence; this enriches future collection only.
ALTER TABLE public_information ADD COLUMN publisher_summary VARCHAR(900);
ALTER TABLE public_information ADD COLUMN summary_truncated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public_information ADD CONSTRAINT information_summary_shape
    CHECK (publisher_summary IS NOT NULL OR summary_truncated = FALSE);
