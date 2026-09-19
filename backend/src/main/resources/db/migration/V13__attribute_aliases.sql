-- Different catalog chunks sometimes name the same spec differently ("phase" / "phases").
-- AttributeSchemaService merges them into one canonical key per class; the aliases are kept so later
-- imports (incl. cached chunk results) and tender lines are translated to the canonical key.
CREATE TABLE attribute_alias (
    id             BIGSERIAL PRIMARY KEY,
    class_id       BIGINT NOT NULL REFERENCES product_class(id) ON DELETE CASCADE,
    alias          TEXT   NOT NULL,
    canonical_name TEXT   NOT NULL,
    UNIQUE (class_id, alias)
);

-- Per-spec value translations, e.g. phase: {"Single":"1","Three":"3"} (keys compared case-insensitively).
ALTER TABLE attribute_def ADD COLUMN value_aliases JSONB NOT NULL DEFAULT '{}';
