-- Supports development databases that applied V7 before price provenance was added.
ALTER TABLE product_price
    ADD COLUMN IF NOT EXISTS source_url TEXT,
    ADD COLUMN IF NOT EXISTS extraction_method VARCHAR(64),
    ADD COLUMN IF NOT EXISTS observed_at TIMESTAMPTZ;

ALTER TABLE product_price_history
    ADD COLUMN IF NOT EXISTS source_url TEXT,
    ADD COLUMN IF NOT EXISTS extraction_method VARCHAR(64),
    ADD COLUMN IF NOT EXISTS observed_at TIMESTAMPTZ;
