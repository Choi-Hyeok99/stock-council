CREATE TABLE manual_sell_intent (
 id UUID PRIMARY KEY,
 scope_id VARCHAR(128) NOT NULL,
 stock_code VARCHAR(6) NOT NULL CHECK(stock_code ~ '^[0-9]{6}$'),
 quantity INTEGER NOT NULL CHECK(quantity>0),
 holding_fingerprint VARCHAR(64) NOT NULL,
 limit_price NUMERIC(20,2) NOT NULL CHECK(limit_price>0),
 commission_rate NUMERIC(16,10) NOT NULL CHECK(commission_rate>=0),
 tax_rate NUMERIC(16,10) NOT NULL CHECK(tax_rate>=0),
 estimated_net_pnl NUMERIC(20,2) NOT NULL,
 quote_at TIMESTAMPTZ NOT NULL,
 created_at TIMESTAMPTZ NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL CHECK(expires_at>created_at),
 order_id BIGINT UNIQUE REFERENCES execution_order(id),
 consumed_at TIMESTAMPTZ,
 CHECK((order_id IS NULL)=(consumed_at IS NULL))
);
CREATE FUNCTION protect_manual_sell_intent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.order_id IS NOT NULL THEN RAISE EXCEPTION 'Manual sale confirmation is immutable'; END IF;
 IF ROW(NEW.id,NEW.scope_id,NEW.stock_code,NEW.quantity,NEW.holding_fingerprint,NEW.limit_price,
        NEW.commission_rate,NEW.tax_rate,NEW.estimated_net_pnl,NEW.quote_at,NEW.created_at,NEW.expires_at)
 IS DISTINCT FROM ROW(OLD.id,OLD.scope_id,OLD.stock_code,OLD.quantity,OLD.holding_fingerprint,OLD.limit_price,
        OLD.commission_rate,OLD.tax_rate,OLD.estimated_net_pnl,OLD.quote_at,OLD.created_at,OLD.expires_at)
 THEN RAISE EXCEPTION 'Manual sale preview cannot be replaced'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER manual_sell_intent_guard BEFORE UPDATE OR DELETE ON manual_sell_intent FOR EACH ROW EXECUTE FUNCTION protect_manual_sell_intent();
