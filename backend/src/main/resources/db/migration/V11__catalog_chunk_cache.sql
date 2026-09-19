-- Extraction results per catalog chunk, so re-uploading the same catalog does not pay for the
-- same LLM calls again. cache_key = sha256(prompt version | model | chunk text); only complete
-- (not cut-off) results are stored.
CREATE TABLE catalog_chunk_cache (
    id            BIGSERIAL PRIMARY KEY,
    cache_key     VARCHAR(64) NOT NULL UNIQUE,
    products      JSONB       NOT NULL,
    product_count INT         NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
