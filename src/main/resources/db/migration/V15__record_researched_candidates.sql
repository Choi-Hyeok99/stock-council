-- A researched company outside the interest top ten is different from a failed safety filter.
ALTER TABLE universe_selection_record DROP CONSTRAINT universe_selection_record_decision_check;
ALTER TABLE universe_selection_record ADD CONSTRAINT universe_selection_record_decision_check
    CHECK (decision IN ('SELECTED', 'RESEARCHED', 'REJECTED', 'DEFERRED'));
