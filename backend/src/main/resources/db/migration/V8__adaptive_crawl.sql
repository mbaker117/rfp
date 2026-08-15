ALTER TABLE supplier
    ADD COLUMN IF NOT EXISTS crawl_allowed_hosts TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS crawl_throttle_ms BIGINT,
    ADD COLUMN IF NOT EXISTS crawl_max_concurrency INT,
    ADD COLUMN IF NOT EXISTS crawl_batch_pages INT,
    ADD COLUMN IF NOT EXISTS crawl_batch_documents INT,
    ADD COLUMN IF NOT EXISTS crawl_max_urls INT,
    ADD COLUMN IF NOT EXISTS crawl_max_duration_minutes INT,
    ADD COLUMN IF NOT EXISTS crawl_robots_fail_closed BOOLEAN;

ALTER TABLE product
    ADD COLUMN IF NOT EXISTS crawl_miss_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS canonical_source_url TEXT,
    ADD COLUMN IF NOT EXISTS last_observed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS crawler_stale BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE product_price
    ADD COLUMN IF NOT EXISTS source_url TEXT,
    ADD COLUMN IF NOT EXISTS extraction_method VARCHAR(64),
    ADD COLUMN IF NOT EXISTS observed_at TIMESTAMPTZ;

ALTER TABLE product_price_history
    ADD COLUMN IF NOT EXISTS source_url TEXT,
    ADD COLUMN IF NOT EXISTS extraction_method VARCHAR(64),
    ADD COLUMN IF NOT EXISTS observed_at TIMESTAMPTZ;

CREATE TABLE crawl_run (
    id BIGSERIAL PRIMARY KEY,
    supplier_id BIGINT NOT NULL REFERENCES supplier(id),
    status VARCHAR(32) NOT NULL,
    mode VARCHAR(64) NOT NULL DEFAULT 'ADAPTIVE',
    config_json JSONB NOT NULL DEFAULT '{}',
    discovered_url_count INT NOT NULL DEFAULT 0,
    fetched_url_count INT NOT NULL DEFAULT 0,
    failed_url_count INT NOT NULL DEFAULT 0,
    rejected_url_count INT NOT NULL DEFAULT 0,
    retried_url_count INT NOT NULL DEFAULT 0,
    pending_url_count INT NOT NULL DEFAULT 0,
    observed_product_count INT NOT NULL DEFAULT 0,
    inserted_product_count INT NOT NULL DEFAULT 0,
    updated_product_count INT NOT NULL DEFAULT 0,
    unchanged_product_count INT NOT NULL DEFAULT 0,
    stale_product_count INT NOT NULL DEFAULT 0,
    completeness_score INT,
    completeness_reason TEXT,
    batch_count INT NOT NULL DEFAULT 0,
    checkpoint_count INT NOT NULL DEFAULT 0,
    cancellation_requested BOOLEAN NOT NULL DEFAULT FALSE,
    failure_category VARCHAR(64),
    failure_details TEXT,
    started_at TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX crawl_run_supplier_status_idx ON crawl_run(supplier_id, status);

CREATE TABLE crawl_url (
    id BIGSERIAL PRIMARY KEY,
    crawl_run_id BIGINT NOT NULL REFERENCES crawl_run(id) ON DELETE CASCADE,
    original_url TEXT NOT NULL,
    normalized_url TEXT NOT NULL,
    host TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    page_type VARCHAR(32) NOT NULL,
    depth INT NOT NULL,
    priority INT NOT NULL,
    parent_url TEXT,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    claimed_at TIMESTAMPTZ,
    fetched_at TIMESTAMPTZ,
    error_category VARCHAR(64),
    error_details TEXT,
    http_status INT,
    content_type TEXT,
    content_length BIGINT,
    etag TEXT,
    last_modified TEXT,
    canonical_url TEXT,
    content_hash TEXT,
    required_partition BOOLEAN NOT NULL DEFAULT FALSE,
    pagination_member BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (crawl_run_id, normalized_url)
);
CREATE INDEX crawl_url_run_status_priority_idx ON crawl_url(crawl_run_id, status, priority DESC);
CREATE INDEX crawl_url_run_next_attempt_idx ON crawl_url(crawl_run_id, next_attempt_at);

CREATE TABLE crawl_product_observation (
    id BIGSERIAL PRIMARY KEY,
    crawl_run_id BIGINT NOT NULL REFERENCES crawl_run(id) ON DELETE CASCADE,
    supplier_id BIGINT NOT NULL REFERENCES supplier(id),
    identity_key TEXT NOT NULL,
    product_name TEXT NOT NULL,
    mpn TEXT,
    product_class_name TEXT,
    attributes_json JSONB NOT NULL DEFAULT '{}',
    price NUMERIC(12,3),
    currency VARCHAR(10),
    source_url TEXT NOT NULL,
    field_provenance_json JSONB NOT NULL DEFAULT '{}',
    extraction_method VARCHAR(64) NOT NULL,
    confidence INT,
    content_hash TEXT,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX crawl_product_observation_supplier_identity_idx
    ON crawl_product_observation(supplier_id, identity_key);
