-- New owner pause, preserving all old policy and order decision evidence.
INSERT INTO execution_policy_adoption(scope_id,entry_order_id,policy,adopted_at)
 SELECT p.scope_id,p.entry_order_id,'DIP_MA20_1_3_REBOUND_0P3_SELL_PAUSED_COST_STUDY_V2',CURRENT_TIMESTAMP
 FROM execution_position p JOIN execution_account a USING(scope_id) WHERE a.environment='PAPER'
 ON CONFLICT DO NOTHING;
