CREATE TABLE company (
    id          BIGSERIAL PRIMARY KEY,
    name        TEXT NOT NULL,
    official_website TEXT,
    country     TEXT NOT NULL DEFAULT 'Jordan',
    scrape_status TEXT NOT NULL DEFAULT 'PENDING',
    last_scraped_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX company_name_idx ON company(lower(name));

CREATE TABLE instrument (
    id               BIGSERIAL PRIMARY KEY,
    company_id       BIGINT NOT NULL REFERENCES company(id),
    description      TEXT NOT NULL,
    normalized_name  TEXT NOT NULL,
    manual_link      TEXT,
    price            NUMERIC(12,3),
    currency         VARCHAR(10) NOT NULL DEFAULT 'JOD',
    raw_data         JSONB,
    is_stale         BOOLEAN NOT NULL DEFAULT FALSE,
    llm_cache_key    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE instrument_price_history (
    id            BIGSERIAL PRIMARY KEY,
    instrument_id BIGINT NOT NULL REFERENCES instrument(id),
    price         NUMERIC(12,3) NOT NULL,
    currency      VARCHAR(10) NOT NULL DEFAULT 'JOD',
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE rfp_request (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT NOT NULL,
    original_filename TEXT NOT NULL,
    file_type         TEXT NOT NULL,
    status            TEXT NOT NULL DEFAULT 'UPLOADED',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE required_instrument (
    id                      BIGSERIAL PRIMARY KEY,
    rfp_request_id          BIGINT NOT NULL REFERENCES rfp_request(id),
    raw_text                TEXT NOT NULL,
    extracted_spec          JSONB,
    matched_instrument_id   BIGINT REFERENCES instrument(id),
    matching_score          INT,
    match_status            TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE scrape_job (
    id          BIGSERIAL PRIMARY KEY,
    company_id  BIGINT NOT NULL REFERENCES company(id),
    status      TEXT NOT NULL DEFAULT 'PENDING',
    error_msg   TEXT,
    started_at  TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
