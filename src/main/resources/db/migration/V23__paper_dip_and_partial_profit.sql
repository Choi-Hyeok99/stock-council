-- Preserve every historical intent. New policy applies to PAPER holdings by explicit owner decision.
ALTER TABLE execution_order ADD COLUMN exit_purpose VARCHAR(24) NOT NULL DEFAULT 'LEGACY'
    CHECK(exit_purpose IN ('LEGACY','TAKE_PROFIT','USER_REQUEST','RISK_EXIT'));
CREATE FUNCTION protect_execution_exit_purpose() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.exit_purpose IS DISTINCT FROM OLD.exit_purpose THEN RAISE EXCEPTION 'order exit purpose is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER execution_exit_purpose_immutable BEFORE UPDATE ON execution_order
 FOR EACH ROW EXECUTE FUNCTION protect_execution_exit_purpose();
CREATE TABLE execution_profit_plan (
 scope_id VARCHAR(64) NOT NULL REFERENCES execution_account(scope_id),
 entry_order_id BIGINT NOT NULL REFERENCES execution_order(id),
 target_quantity INTEGER NOT NULL CHECK(target_quantity>0),
 created_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(scope_id,entry_order_id)
);
CREATE TRIGGER execution_profit_plan_immutable BEFORE UPDATE OR DELETE ON execution_profit_plan
 FOR EACH ROW EXECUTE FUNCTION protect_financial_evidence();
CREATE TABLE execution_policy_adoption (
 scope_id VARCHAR(64) NOT NULL REFERENCES execution_account(scope_id),
 entry_order_id BIGINT NOT NULL REFERENCES execution_order(id),
 policy VARCHAR(128) NOT NULL, adopted_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(scope_id,entry_order_id,policy)
);
CREATE TRIGGER execution_policy_adoption_immutable BEFORE UPDATE OR DELETE ON execution_policy_adoption
 FOR EACH ROW EXECUTE FUNCTION protect_financial_evidence();
CREATE FUNCTION validate_profit_policy_episode() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE root execution_order%ROWTYPE;
BEGIN
 SELECT * INTO root FROM execution_order WHERE id=NEW.entry_order_id;
 IF NOT FOUND OR root.scope_id<>NEW.scope_id OR root.side<>'BUY' OR root.entry_order_id IS NOT NULL THEN
  RAISE EXCEPTION 'profit policy ownership mismatch';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER profit_plan_episode_guard BEFORE INSERT ON execution_profit_plan
 FOR EACH ROW EXECUTE FUNCTION validate_profit_policy_episode();
CREATE TRIGGER policy_adoption_episode_guard BEFORE INSERT ON execution_policy_adoption
 FOR EACH ROW EXECUTE FUNCTION validate_profit_policy_episode();
INSERT INTO execution_policy_adoption(scope_id,entry_order_id,policy,adopted_at)
 SELECT p.scope_id,p.entry_order_id,'DIP_MA20_1_3_REBOUND_0P3_NO_LOSS_EXIT_TP3_HALF_V1',CURRENT_TIMESTAMP
 FROM execution_position p JOIN execution_account a USING(scope_id) WHERE a.environment='PAPER';
