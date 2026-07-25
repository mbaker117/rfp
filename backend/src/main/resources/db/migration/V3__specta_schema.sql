CREATE TABLE product_class (
  id           BIGSERIAL PRIMARY KEY,
  name         TEXT NOT NULL UNIQUE,
  auto_created BOOL NOT NULL DEFAULT FALSE,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE attribute_def (
  id             BIGSERIAL PRIMARY KEY,
  class_id       BIGINT NOT NULL REFERENCES product_class(id),
  name           TEXT NOT NULL,
  label          TEXT NOT NULL,
  datatype       TEXT NOT NULL CHECK (datatype IN ('numeric','text','bool','enum')),
  match_op       TEXT NOT NULL CHECK (match_op IN ('eq','gte','lte')),
  canonical_unit TEXT,
  allowed_values TEXT[] DEFAULT '{}',
  UNIQUE (class_id, name)
);

CREATE TABLE unit_conversion (
  dimension TEXT NOT NULL,
  from_unit TEXT NOT NULL,
  to_unit   TEXT NOT NULL,
  factor    NUMERIC NOT NULL,
  addend    NUMERIC NOT NULL DEFAULT 0,
  PRIMARY KEY (dimension, from_unit, to_unit)
);

CREATE TABLE supplier (
  id               BIGSERIAL PRIMARY KEY,
  name             TEXT NOT NULL,
  official_website TEXT,
  contact_email    TEXT,
  contact_phone    TEXT,
  country          TEXT,
  description      TEXT,
  categories       TEXT[] DEFAULT '{}',
  scrape_status    TEXT NOT NULL DEFAULT 'PENDING',
  last_scraped_at  TIMESTAMPTZ,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX supplier_name_idx ON supplier(LOWER(name));

CREATE TABLE product (
  id          BIGSERIAL PRIMARY KEY,
  supplier_id BIGINT NOT NULL REFERENCES supplier(id),
  class_id    BIGINT REFERENCES product_class(id),
  name        TEXT NOT NULL,
  mpn         TEXT,
  attributes  JSONB NOT NULL DEFAULT '{}',
  source      TEXT NOT NULL CHECK (source IN ('upload','scrape')),
  is_stale    BOOL NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX product_supplier_idx ON product(supplier_id);
CREATE INDEX product_class_idx    ON product(class_id);

CREATE TABLE product_price (
  product_id  BIGINT PRIMARY KEY REFERENCES product(id),
  price       NUMERIC(12,3),
  currency    TEXT NOT NULL DEFAULT 'JOD',
  as_of       DATE,
  source_file TEXT
);

CREATE TABLE product_price_history (
  id          BIGSERIAL PRIMARY KEY,
  product_id  BIGINT NOT NULL REFERENCES product(id),
  price       NUMERIC(12,3) NOT NULL,
  currency    TEXT NOT NULL DEFAULT 'JOD',
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE catalog_ingest (
  id          BIGSERIAL PRIMARY KEY,
  supplier_id BIGINT NOT NULL REFERENCES supplier(id),
  kind        TEXT NOT NULL CHECK (kind IN ('company_upload','admin_upload','scrape')),
  filename    TEXT,
  status      TEXT NOT NULL DEFAULT 'PENDING'
                CHECK (status IN ('PENDING','RUNNING','DONE','FAILED')),
  error_msg   TEXT,
  started_at  TIMESTAMPTZ,
  finished_at TIMESTAMPTZ
);

CREATE TABLE app_user (
  id            BIGSERIAL PRIMARY KEY,
  username      TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  role          TEXT NOT NULL DEFAULT 'USER' CHECK (role IN ('USER','ADMIN'))
);

CREATE TABLE tender (
  id         BIGSERIAL PRIMARY KEY,
  user_id    BIGINT NOT NULL REFERENCES app_user(id),
  filename   TEXT NOT NULL,
  file_type  TEXT NOT NULL,
  status     TEXT NOT NULL DEFAULT 'uploading'
               CHECK (status IN ('uploading','extracting','matching','done','failed')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE tender_supplier (
  tender_id   BIGINT NOT NULL REFERENCES tender(id),
  supplier_id BIGINT NOT NULL REFERENCES supplier(id),
  PRIMARY KEY (tender_id, supplier_id)
);

CREATE TABLE tender_line (
  id          BIGSERIAL PRIMARY KEY,
  tender_id   BIGINT NOT NULL REFERENCES tender(id),
  line_no     TEXT,
  raw_text    TEXT NOT NULL,
  description TEXT,
  qty         NUMERIC,
  qty_unit    TEXT,
  class_id    BIGINT REFERENCES product_class(id),
  attributes  JSONB NOT NULL DEFAULT '{}',
  status      TEXT NOT NULL DEFAULT 'extracted'
                CHECK (status IN ('extracted','matched','partial','not_found','unclassified'))
);
CREATE INDEX tender_line_tender_idx ON tender_line(tender_id);

CREATE TABLE match_result (
  id                 BIGSERIAL PRIMARY KEY,
  line_id            BIGINT NOT NULL UNIQUE REFERENCES tender_line(id),
  product_id         BIGINT REFERENCES product(id),
  match_type         TEXT CHECK (match_type IN ('exact','spec')),
  score              INT CHECK (score BETWEEN 0 AND 100),
  attribute_verdicts JSONB NOT NULL DEFAULT '[]',
  status             TEXT NOT NULL CHECK (status IN ('matched','partial','not_found')),
  alternatives       JSONB NOT NULL DEFAULT '[]'
);
