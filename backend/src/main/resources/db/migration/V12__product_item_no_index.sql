-- Catalog uploads match existing products by the supplier's item number first
-- (ProductRepository.findAllBySupplierIdAndItemNo). Not a partial index: that query cannot
-- repeat an "attributes ? 'item_no'" predicate because "?" is a JDBC parameter placeholder.
CREATE INDEX product_supplier_item_no_idx
    ON product (supplier_id, upper(attributes->>'item_no'));
