-- Adopt the owner-approved net 5% partial profit and 2–3m PAPER sizing policy.
-- Historical decisions, profit plans, automation state and loss review state remain immutable here.
INSERT INTO execution_policy_adoption(scope_id,entry_order_id,policy,adopted_at)
 SELECT p.scope_id,p.entry_order_id,'DIP_MA20_1_3_REBOUND_0P3_NO_LOSS_EXIT_TP5_HALF_SIZE2_3M_V3',CURRENT_TIMESTAMP
 FROM execution_position p JOIN execution_account a USING(scope_id) WHERE a.environment='PAPER'
 ON CONFLICT DO NOTHING;
