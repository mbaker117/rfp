-- Replace the plain partial index with a UNIQUE partial index so that
-- (supplier_id, identity_key) is enforced at the database level, preventing
-- duplicate crawler products from being created under concurrent reconciliation.
DROP INDEX IF EXISTS product_supplier_identity_key_idx;
CREATE UNIQUE INDEX product_supplier_identity_key_idx
    ON product(supplier_id, identity_key)
    WHERE identity_key IS NOT NULL;
