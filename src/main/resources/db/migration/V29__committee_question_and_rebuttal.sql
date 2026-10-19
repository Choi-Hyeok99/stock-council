-- Append-only old opinions remain unchanged; one bounded question/answer round is added.
ALTER TABLE committee_opinion DROP CONSTRAINT committee_opinion_phase_check;
ALTER TABLE committee_opinion ADD CONSTRAINT committee_opinion_phase_check CHECK (phase IN ('INDEPENDENT','DEBATE','FINAL'));
ALTER TABLE committee_ai_call DROP CONSTRAINT committee_ai_call_phase_check;
ALTER TABLE committee_ai_call ADD CONSTRAINT committee_ai_call_phase_check CHECK (phase IN ('INDEPENDENT','DEBATE','FINAL','SUMMARY'));
