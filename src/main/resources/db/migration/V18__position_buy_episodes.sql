-- BUY additions and SELL orders reference the first BUY of the same ownership episode.
-- Existing intent evidence and ownership links are preserved.
CREATE INDEX execution_episode_orders ON execution_order(scope_id,entry_order_id,side);
CREATE FUNCTION validate_execution_episode_link() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE root execution_order%ROWTYPE;
BEGIN
    IF TG_OP='UPDATE' AND (NEW.entry_order_id IS DISTINCT FROM OLD.entry_order_id
        OR NEW.scope_id IS DISTINCT FROM OLD.scope_id OR NEW.stock_code IS DISTINCT FROM OLD.stock_code
        OR NEW.side IS DISTINCT FROM OLD.side) THEN
        RAISE EXCEPTION 'Execution ownership episode is immutable';
    END IF;
    IF NEW.entry_order_id IS NOT NULL THEN
        SELECT * INTO root FROM execution_order WHERE id=NEW.entry_order_id;
        IF NOT FOUND OR root.side<>'BUY' OR root.entry_order_id IS NOT NULL
            OR root.scope_id<>NEW.scope_id OR root.stock_code<>NEW.stock_code OR root.id=NEW.id THEN
            RAISE EXCEPTION 'Execution ownership episode mismatch';
        END IF;
    ELSIF NEW.side='SELL' THEN
        RAISE EXCEPTION 'SELL needs a BUY ownership episode';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER execution_episode_link_guard BEFORE INSERT OR UPDATE ON execution_order
    FOR EACH ROW EXECUTE FUNCTION validate_execution_episode_link();
CREATE VIEW execution_episode_fill AS
SELECT COALESCE(o.entry_order_id,o.id) AS entry_order_id,o.scope_id,o.stock_code,o.side,
    f.order_id,f.quantity,f.gross_amount,f.commission,f.tax,f.gross_pnl,f.net_pnl,f.occurred_at
FROM execution_fill f JOIN execution_order o ON o.id=f.order_id;
