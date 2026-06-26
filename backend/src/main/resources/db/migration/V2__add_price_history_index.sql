CREATE INDEX IF NOT EXISTS idx_instrument_price_history_instrument_id
    ON instrument_price_history(instrument_id);
CREATE INDEX IF NOT EXISTS idx_instrument_company_stale
    ON instrument(company_id, is_stale);
