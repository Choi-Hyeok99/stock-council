CREATE TABLE paper_capital_event (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    balance_before NUMERIC(19, 2) NOT NULL,
    balance_after NUMERIC(19, 2) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    reason TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Correct the unused experiment's initial capital; never erase trading results.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM paper_fill) OR EXISTS (SELECT 1 FROM paper_position)
       OR EXISTS (SELECT 1 FROM paper_order)
       OR EXISTS (SELECT 1 FROM paper_account WHERE realized_pnl <> 0
                  OR gross_realized_pnl <> 0 OR cumulative_costs <> 0 OR cash_balance <> 300000) THEN
        RAISE EXCEPTION 'Starting capital correction requires an unused internal paper account';
    END IF;
    INSERT INTO paper_capital_event (balance_before, balance_after, amount, reason)
    SELECT cash_balance, 10000000, 10000000 - cash_balance,
           'User requested KRW 10,000,000 starting capital; not a broker balance synchronization'
    FROM paper_account WHERE id = 1;
    UPDATE paper_account SET cash_balance = 10000000, updated_at = CURRENT_TIMESTAMP WHERE id = 1;
END $$;
