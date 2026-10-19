ALTER TABLE execution_order ADD COLUMN decision_explanation JSONB;
ALTER TABLE execution_order ADD CONSTRAINT execution_decision_object
    CHECK (decision_explanation IS NULL OR jsonb_typeof(decision_explanation)='object');

-- Historical orders stay NULL. Never relabel a reconstruction as order-time evidence.
CREATE FUNCTION preserve_order_decision_explanation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.decision_explanation IS DISTINCT FROM OLD.decision_explanation THEN
        RAISE EXCEPTION 'Order decision evidence is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER execution_decision_immutable BEFORE UPDATE OF decision_explanation ON execution_order
    FOR EACH ROW EXECUTE FUNCTION preserve_order_decision_explanation();
