CREATE TABLE proposal (
    id               BIGSERIAL PRIMARY KEY,
    tender_id        BIGINT NOT NULL REFERENCES tender(id),
    variant          VARCHAR(32) NOT NULL,
    acceptance_rate  NUMERIC(5,2),
    match_score      NUMERIC(5,2),
    is_complete      BOOLEAN NOT NULL DEFAULT FALSE,
    status           VARCHAR(32) NOT NULL DEFAULT 'GENERATING',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE proposal_line (
    id                      BIGSERIAL PRIMARY KEY,
    proposal_id             BIGINT NOT NULL REFERENCES proposal(id) ON DELETE CASCADE,
    line_id                 BIGINT NOT NULL REFERENCES tender_line(id),
    selected_product_id     BIGINT REFERENCES product(id),
    match_score             NUMERIC(5,2),
    acceptance_probability  NUMERIC(5,2),
    llm_reasoning           TEXT,
    is_overridden           BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (proposal_id, line_id)
);
