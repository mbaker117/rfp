-- Add identity_key to product for crawl reconciliation lookups.
-- Nullable to preserve backward compatibility with products created before the adaptive crawler.
ALTER TABLE product
    ADD COLUMN IF NOT EXISTS identity_key TEXT;

-- Index for efficient lookup by supplier + identity key
CREATE INDEX IF NOT EXISTS product_supplier_identity_key_idx
    ON product(supplier_id, identity_key)
    WHERE identity_key IS NOT NULL;

-- Track when a crawl run was reconciled to ensure idempotency.
ALTER TABLE crawl_run
    ADD COLUMN IF NOT EXISTS reconciled_at TIMESTAMPTZ;
