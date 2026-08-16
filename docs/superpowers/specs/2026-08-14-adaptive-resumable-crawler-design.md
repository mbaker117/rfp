# Adaptive Resumable Catalog Crawler Design

## Objective

Replace the current shallow, single-pass supplier scraper with a safe, durable, adaptive crawler that supports both small sites and catalogs containing thousands of products. The crawler must preserve provenance, resume interrupted work, avoid false stale-product decisions, and remain bounded by deterministic safety controls.

## Scope

This design covers supplier website crawling, catalog discovery, content extraction, LLM-assisted classification, product reconciliation, crawl operations, and admin visibility. Existing catalog file uploads remain independent and continue to use their current ingestion path.

The existing `POST /suppliers/{id}/catalog/scrape` contract remains available. It will enqueue a durable crawl run instead of executing one monolithic crawl operation.

## Architecture

Each supplier scrape is represented by a durable crawl run. A coordinator creates the run and schedules resumable batches. A persistent URL frontier records discovered work and allows a run to continue across process restarts or batch boundaries.

The pipeline is:

1. Load supplier crawl configuration.
2. Validate the starting URL and allowed hosts.
3. Discover sitemaps, feeds, public catalog APIs, and homepage links.
4. Add normalized URLs to a prioritized crawl frontier.
5. Fetch with HTTP first and a shared Playwright browser fallback.
6. Parse HTML, structured data, embedded JSON, feeds, and supported documents.
7. Use the LLM to classify page types, prioritize ambiguous links, propose extraction mappings, and identify catalog partitions.
8. Extract and normalize products deterministically.
9. Store product observations with provenance.
10. Calculate crawl completeness.
11. Reconcile a complete snapshot against existing supplier products.

Small sites may finish in one batch. Large catalogs continue through background batches until all required partitions and catalog branches are exhausted. Limits apply per batch, with a configurable total-run ceiling to prevent infinite discovery.

The LLM guides crawl priority and classification but cannot override security, host, concurrency, time, response-size, or total-run limits.

## Host Policy and SSRF Protection

The crawler automatically permits the supplier website's registrable root domain and its subdomains. A per-supplier allowlist supports separate documentation, feed, CDN, or catalog domains.

Every initial request and redirect must pass the same validation:

- Only HTTP and HTTPS are permitted.
- Resolve DNS before connecting.
- Reject loopback, private, link-local, multicast, unspecified, and cloud metadata IP ranges for IPv4 and IPv6.
- Revalidate the destination after DNS resolution and after every redirect.
- Reject user-info components and malformed or ambiguous hostnames.
- Prevent DNS rebinding by connecting only to a validated resolution or revalidating at connection time.
- Do not send credentials, stored cookies, or form submissions.

The crawler respects `robots.txt`. Robots failures use a configurable fail-closed policy for production, with the decision recorded in the crawl run.

## Persistence Model

Flyway migrations add the following structures.

### `crawl_run`

- Supplier reference.
- Status: `QUEUED`, `DISCOVERING`, `CRAWLING`, `EXTRACTING`, `RECONCILING`, `COMPLETE`, `PARTIAL`, `FAILED`, or `CANCELLED`.
- Crawl mode and immutable configuration snapshot.
- Counts for discovered, fetched, failed, rejected, retried, and pending URLs.
- Counts for observed, inserted, updated, unchanged, and stale products.
- Completeness score and a machine-readable completeness reason.
- Batch/checkpoint counters.
- Started, heartbeat, finished, and updated timestamps.
- Cancellation request and terminal failure details.

### `crawl_url`

- Crawl-run reference.
- Original and normalized URL.
- Host, page type, depth, priority, and parent URL.
- Fetch state, retry count, next-attempt timestamp, and terminal error category.
- HTTP status, content type, content length, ETag, Last-Modified, canonical URL, and content hash.
- Flags for required catalog partitions and pagination membership.
- Created, fetched, and updated timestamps.

A uniqueness constraint on `(crawl_run_id, normalized_url)` prevents duplicate work.

### `crawl_product_observation`

- Crawl-run and supplier references.
- Stable product identity key.
- Product name, MPN, class, attributes, price, and currency payload.
- Source URL and optional per-field provenance.
- Extraction method and confidence.
- Content hash and observation timestamp.

### Supplier crawl configuration

- Explicit related-host allowlist.
- Optional per-supplier throttling, concurrency, batch, and total-run overrides.
- Robots policy override where operationally authorized.

## URL Frontier and Discovery

URL normalization uses `java.net.URI` and resolves relative references against the URL of the page containing the link. Normalization removes fragments, normalizes host casing and default ports, safely normalizes paths, and handles query parameters according to configurable tracking-parameter rules.

Discovery sources are prioritized as follows:

1. Sitemap indexes and sitemaps.
2. Explicit feeds and public catalog APIs.
3. Structured navigation and category links.
4. Pagination and product links.
5. Manuals, datasheets, and supported catalog documents.
6. Ambiguous same-policy links selected by the LLM.

The crawler detects sitemap indexes recursively within hard limits. Listing and pagination templates can create partitions so large catalogs are processed without loading the whole catalog into one model request.

The LLM receives a bounded set of normalized link candidates and page metadata. Its output must conform to a validated schema containing page type, priority, partition hints, and crawl recommendation. Rejected or malformed LLM output falls back to deterministic rules.

## Fetching and Rendering

HTTP is the default fetch mechanism. A fetch response is subject to connect, read, total-request, content-length, and content-type limits.

Playwright is used only when deterministic heuristics identify sparse or JavaScript-rendered content. One browser is reused per worker or crawl batch, with isolated browser contexts and bounded pages. Network-idle is not the sole readiness criterion; the fetcher also uses DOM/content stabilization with a maximum wait.

Requests use bounded concurrency and per-host rate limiting. Transient failures retry with exponential backoff and jitter. Permanent HTTP failures, policy rejections, and parse failures use distinct error categories. A failed individual page does not abort the run, but failures on required partitions prevent a complete result.

Responses may be cached conditionally using ETag and Last-Modified. Content hashes prevent re-extraction of unchanged pages and duplicate content reachable through multiple URLs.

## Parsing and Product Extraction

Jsoup replaces regex-based HTML parsing. The parser extracts:

- Visible semantic text and headings.
- Navigation, category, pagination, canonical, and alternate links.
- Product tables and definition lists.
- JSON-LD and Open Graph metadata.
- Embedded JSON and discoverable public API endpoints.
- Product identifiers, prices, currencies, descriptions, images, manuals, and datasheets.
- Supported catalog documents, subject to size and count limits.

Linked manufacturer manuals and datasheets are first-class product sources. The crawler downloads supported PDF, DOC, DOCX, XLS, XLSX, plain-text, and HTML manuals within the same host-policy, robots, response-size, document-count, and timeout limits. It parses their specifications and merges them into the associated product rather than storing only the link. Manual-derived fields retain document URL plus page, sheet, or section provenance. Manufacturer structured data and public product APIs outrank manual-derived values; manual-derived values outrank listing-page extraction when conflicts occur.

Product pages are processed independently. Catalog-wide character truncation is removed. Listing pages may produce provisional observations, which higher-confidence product-page observations enrich or supersede.

Price is part of every product observation when present. The crawler captures the numeric amount, ISO currency, source URL, extraction method, and observation time. Public API and JSON-LD Offer values outrank product-page values, which outrank manufacturer-document and listing values. Currency is never inferred from supplier location alone; values without a trustworthy currency remain unresolved. Reconciliation updates `product_price` and appends the prior value to `product_price_history` only when amount or currency changes.

The LLM is called on page-sized or product-group-sized batches with a versioned JSON schema. Responses are validated before use. Malformed or truncated output retries with a smaller batch. Deterministic merging resolves duplicate observations by stable identity, source confidence, extraction confidence, and freshness.

Every stored product keeps a canonical source URL. Where practical, individual extracted values retain their source URL and extraction method.

## Product Identity and Reconciliation

Product identity is supplier plus normalized MPN when an MPN exists. Without an MPN, identity uses a stable fingerprint derived from normalized product name, product class, and canonical source URL. Identity collisions are recorded for administrative review rather than silently merged.

A run is `COMPLETE` only when:

- All required sitemap, feed, API, catalog, and pagination partitions are exhausted.
- No required frontier items remain pending or terminally failed.
- The total-run ceiling was not reached.
- Extraction and persistence completed for all successfully fetched product-bearing pages.

Fetch failures, unresolved partitions, budget exhaustion, cancellation, or incomplete extraction produce `PARTIAL`, `FAILED`, or `CANCELLED`, never `COMPLETE`.

Partial, failed, and cancelled runs never mark products stale and never increment missing-product counters.

After a complete run, products not observed receive one consecutive-complete-snapshot miss. A product becomes stale only after it is absent from two consecutive complete snapshots. A later complete observation clears the miss count and restores an automatically stale product. Manual administrative state changes remain distinguishable from crawler state.

## Batch Execution and Recovery

Workers claim frontier records transactionally to avoid duplicate processing. Each batch has configurable limits for page count, document count, concurrency, per-host delay, and elapsed time. The coordinator schedules another batch while eligible frontier work remains.

The run heartbeat identifies abandoned work. On restart, expired claims return to the pending frontier without losing completed observations. Cancellation stops new claims, allows in-flight work to finish safely, and records the run as cancelled.

A total-run ceiling limits discovered URLs, observations, elapsed wall-clock duration, and repeated template expansion. Reaching a ceiling produces a partial run with an explicit reason. An administrator can adjust configuration and resume the same run.

## Operations and Admin UI

The supplier admin page displays:

- Current and historical crawl runs.
- Run phase and completion reason.
- Discovered, fetched, failed, rejected, retrying, and pending page counts.
- Observed, inserted, updated, unchanged, and stale product counts.
- Current batch, heartbeat, elapsed time, and configured limits.
- Expandable structured errors and rejected URLs.
- Warnings that partial runs cannot stale products.
- Controls to resume, cancel, retry failed pages, and start a fresh run.

Product details display canonical source URL, extraction method, observation time, and available field provenance.

The existing job polling mechanism is extended to expose crawl-run progress without requiring a new frontend transport.

## Error Handling and Observability

Errors use stable categories such as policy rejection, robots rejection, DNS failure, timeout, HTTP failure, unsupported content, parse failure, extraction failure, persistence failure, and run-budget exhaustion.

Logs include crawl run and URL record identifiers but exclude full page bodies and secrets. Metrics cover queue depth, fetch latency, response types, retry rates, Playwright fallback rate, extraction yield, duplicate rate, completeness, and products per run.

Silent exception swallowing is removed. Recoverable errors are recorded and retried; terminal errors remain visible and influence completeness.

## Compatibility and Rollout

Existing supplier products and uploads remain valid. New crawl tables are additive. Existing scrape history remains readable but is not backfilled into crawl runs.

Rollout occurs behind a configuration flag:

1. Schema and read-only UI support.
2. New crawler in shadow mode without reconciliation.
3. Reconciliation enabled for selected suppliers.
4. General enablement after large-catalog and failure-mode validation.
5. Removal of the old monolithic crawler after successful migration.

## Testing Strategy

Unit tests cover URL normalization, URI resolution, redirect validation, DNS/IP policy, host allowlists, robots decisions, prioritization, content limits, extraction schemas, product identity, deterministic merging, completeness, and stale reconciliation.

Integration fixtures cover:

- Sitemap indexes and nested sitemaps.
- Feeds and public JSON APIs.
- Category traversal and pagination.
- JSON-LD, embedded JSON, tables, manuals, and supported PDFs.
- Sparse JavaScript pages and Playwright fallback.
- Redirects to permitted, rejected, private, and metadata destinations.
- Retries, backoff, cancellation, expired claims, and process restart recovery.
- Duplicate URLs, duplicate content, and product identity collisions.
- Partial versus complete runs.
- Two consecutive complete-snapshot misses.
- Malformed and truncated LLM output.
- Synthetic catalogs exceeding 5,000 products.

Controller and frontend tests cover enqueue compatibility, progress contracts, run controls, partial-run warnings, errors, and product provenance.

## Acceptance Criteria

- A small conventional supplier site completes and reconciles in one or more durable batches.
- A synthetic catalog with more than 5,000 products completes through resumable batches without catalog-wide LLM truncation.
- A process restart resumes pending work without duplicating completed observations.
- Private, local, metadata, disallowed, and redirect-derived unsafe destinations are never fetched.
- Automatic subdomains and explicitly allowed separate hosts are supported.
- Incomplete runs cannot mark products stale.
- Products become stale only after absence from two consecutive complete snapshots.
- Every product observation has a source URL and extraction method.
- Supported linked manuals and datasheets contribute product specifications with document-level provenance.
- Available product prices include currency, source, observation time, and change history without guessed currencies.
- Administrators can understand, cancel, resume, and retry crawl work from the supplier page.
- Existing upload ingestion and scrape endpoint compatibility are preserved.
