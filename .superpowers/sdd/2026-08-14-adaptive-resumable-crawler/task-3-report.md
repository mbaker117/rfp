# Task 3 Report: Robots Rules and Bounded Fetching

## Status

Implemented and verified Task 3. The implementation commit is `03008ec` (`feat: add robots-aware bounded crawl fetching`).

## Files

- Modified `backend/pom.xml`
  - Added Jsoup `1.18.3`.
  - Added crawler-commons `1.6`.
- Added `backend/src/main/kotlin/com/rfp/service/crawl/RobotsPolicyService.kt`
  - Origin-scoped expiring robots cache.
  - Per-user-agent parsing from cached robots content.
  - Bounded retrieval attempts and bounded robots response streaming.
  - Automatic redirects disabled; unresolved policy fails closed.
- Added `backend/src/main/kotlin/com/rfp/service/crawl/CrawlFetcher.kt`
  - Task 2 `CrawlPolicy.validate` is applied before every target request and immediately to every resolved redirect destination.
  - OkHttp automatic HTTP/HTTPS redirects are disabled and redirects are followed manually.
  - Response bodies are streamed with a one-byte sentinel beyond the configured ceiling, stopping before unbounded/full allocation.
  - Accepted HTML, XHTML, XML, JSON, text, PDF, Word, and Excel MIME types are enforced.
  - Conditional ETag/Last-Modified requests and stored content reuse on `304` are supported.
  - Fetch metadata and SHA-256 content hashes are returned.
- Added `backend/src/main/kotlin/com/rfp/service/crawl/PlaywrightRenderer.kt`
  - Lazily creates one shared browser per renderer/application-worker instance.
  - Creates and closes a fresh browser context for every render.
  - Uses `@PreDestroy` to close the browser and Playwright runtime.
  - Starts only for sparse HTML/XHTML or an explicit parser JavaScript signal; documents and meaningful HTTP HTML bypass rendering.
  - Revalidates routed and final navigation destinations with Task 2 policy and robots decisions.
  - Waits for repeated stable DOM snapshots within one hard maximum that also covers navigation; it does not rely on `NETWORKIDLE`.
- Added `backend/src/test/kotlin/com/rfp/service/crawl/RobotsPolicyServiceTest.kt`
- Added `backend/src/test/kotlin/com/rfp/service/crawl/CrawlFetcherTest.kt`

## Robots Parser Choice

Selected `com.github.crawler-commons:crawler-commons:1.6`. It is the maintained December 2025 release documented by the project, implements RFC 9309-oriented robots parsing, requires Java 11 or newer, and is therefore compatible with this project's Java 21 runtime. The version is pinned explicitly rather than inherited. Primary references: [crawler-commons project and release information](https://github.com/crawler-commons/crawler-commons) and [Maven Central artifact index](https://repo1.maven.org/maven2/com/github/crawler-commons/crawler-commons/).

## TDD Evidence

### Initial RED

Command (the prescribed `mvn` executable was not on PATH, so the installed Maven 3.8.6 binary and existing local repository were used):

```powershell
& 'C:\Users\moham\.m2\wrapper\dists\apache-maven-3.8.6-bin\1ks0nkde5v1pk9vtc31i9d0lcd\apache-maven-3.8.6\bin\mvn.cmd' test '-Dtest=RobotsPolicyServiceTest,CrawlFetcherTest' '-Dmaven.repo.local=C:\Users\moham\.m2\repository'
```

Result: `BUILD FAILURE` during test compilation, for the expected missing production API: unresolved `RobotsPolicyService`, `RobotsDecision`, `CrawlFetcher`, `CrawlFetchRequest`, `StoredFetchContent`, `FetchResult`, and `FetchError` references.

The first environment attempt with bare `mvn` also failed before Maven execution because `mvn` was not on PATH. The first installed-Maven attempt was sandbox-blocked while creating dependency files under `.m2`; after approved dependency resolution, the expected compile RED above was observed.

### Cache Correctness RED

Command:

```powershell
& 'C:\Users\moham\.m2\wrapper\dists\apache-maven-3.8.6-bin\1ks0nkde5v1pk9vtc31i9d0lcd\apache-maven-3.8.6\bin\mvn.cmd' test '-Dtest=RobotsPolicyServiceTest' '-Dmaven.repo.local=C:\Users\moham\.m2\repository'
```

Result: `BUILD FAILURE`, 4 tests run / 1 failure. `cached origin applies rules for each requesting user-agent` expected `Allowed` but received `Disallowed`, proving the initial cache incorrectly retained rules parsed for the first agent. The cache was changed to retain the origin document and parse rules per agent.

### Renderer Lifecycle RED

Command:

```powershell
& 'C:\Users\moham\.m2\wrapper\dists\apache-maven-3.8.6-bin\1ks0nkde5v1pk9vtc31i9d0lcd\apache-maven-3.8.6\bin\mvn.cmd' test '-Dtest=CrawlFetcherTest' '-Dmaven.repo.local=C:\Users\moham\.m2\repository'
```

Result: `BUILD FAILURE` during test compilation because the wished-for renderer factory seam did not exist. The production renderer was then implemented with the tested shared-browser/fresh-context lifecycle.

### Document Render-Gate RED

Command:

```powershell
& 'C:\Users\moham\.m2\wrapper\dists\apache-maven-3.8.6-bin\1ks0nkde5v1pk9vtc31i9d0lcd\apache-maven-3.8.6\bin\mvn.cmd' test '-Dtest=CrawlFetcherTest#does not render a supported document even when its body is small' '-Dmaven.repo.local=C:\Users\moham\.m2\repository'
```

Result: `BUILD FAILURE`, 1 test run / 1 failure. The renderer returned `Rejected(ROBOTS_UNAVAILABLE)` instead of returning the PDF HTTP result unchanged. The render gate was narrowed to HTML/XHTML.

## GREEN and Verification Evidence

Focused required suite:

```powershell
& 'C:\Users\moham\.m2\wrapper\dists\apache-maven-3.8.6-bin\1ks0nkde5v1pk9vtc31i9d0lcd\apache-maven-3.8.6\bin\mvn.cmd' test '-Dtest=RobotsPolicyServiceTest,CrawlFetcherTest' '-Dmaven.repo.local=C:\Users\moham\.m2\repository'
```

Result: `BUILD SUCCESS`; 14 tests run, 0 failures, 0 errors, 0 skipped. No external network access was used by the tests; HTTP behavior used local MockWebServer instances and Playwright lifecycle behavior used interface test doubles.

Compile:

```powershell
& 'C:\Users\moham\.m2\wrapper\dists\apache-maven-3.8.6-bin\1ks0nkde5v1pk9vtc31i9d0lcd\apache-maven-3.8.6\bin\mvn.cmd' compile '-DskipTests' '-Dmaven.repo.local=C:\Users\moham\.m2\repository'
```

Result: `BUILD SUCCESS`. The only compiler warning is a pre-existing deprecated `URL(String)` constructor in legacy `ScrapeService.kt`.

Full backend regression suite:

```powershell
& 'C:\Users\moham\.m2\wrapper\dists\apache-maven-3.8.6-bin\1ks0nkde5v1pk9vtc31i9d0lcd\apache-maven-3.8.6\bin\mvn.cmd' test '-Dmaven.repo.local=C:\Users\moham\.m2\repository'
```

Result: `BUILD SUCCESS`; 86 tests run, 0 failures, 0 errors, 0 skipped. Existing environment warnings include Surefire's no-fork warning, MockK/ByteBuddy dynamic-agent warnings, and PDFBox being unable to write `C:\.pdfbox.cache`; none caused test failures.

Additional check:

```powershell
git diff --check
```

Result: exit code 0.

## Self-Review

- Confirmed OkHttp automatic redirects are disabled in both HTTP fetching and robots retrieval.
- Confirmed each manually resolved HTTP redirect is canonicalized by Task 2 `UrlCanonicalizer` and rejected before any redirected request if Task 2 `CrawlPolicy` fails.
- Confirmed robots-disallowed and robots-unavailable targets do not issue the product request.
- Confirmed robots failures are bounded by `maxAttempts` and fail closed.
- Confirmed the robots cache is origin-scoped, expires, and does not leak one agent's parsed rules to another agent.
- Confirmed known and chunked/unknown-length bodies are bounded before unrestricted allocation.
- Confirmed unsupported MIME types are rejected and never returned as crawl content.
- Confirmed `304` responses preserve stored body, content type, validators, and content hash.
- Confirmed the browser is shared while contexts are isolated and closed per render, and `@PreDestroy` closes shared resources.
- Confirmed the DOM wait uses stabilization checks under an absolute maximum rather than `NETWORKIDLE`.
- Confirmed no unrelated repository files were changed in the implementation commit.

## Concerns / Follow-Up

- Task 3 produces the new fetcher and renderer but intentionally does not replace the legacy fetch logic in `ScrapeService`; wiring occurs in the later orchestration task. That wiring must register a single `PlaywrightRenderer` per application worker so its `@PreDestroy` lifecycle is active.
- Playwright binaries are not launched in unit tests; lifecycle and routing orchestration are tested through Playwright interfaces. A later integration test should exercise a local JavaScript fixture after the renderer is wired into the crawl worker.

## Review Fix Round 1

### Boundary Changes

- Added `ValidatedHttpTransport`, which executes exactly one HTTP hop after a `DestinationValidator` decision. It snapshots the decision's exact resolved addresses into an OkHttp `Dns`, preserves the original URI hostname for Host/TLS SNI, disables proxying, connection reuse, automatic redirects, and retries, applies a total call deadline, streams under a byte ceiling, and closes every response.
- Routed robots retrieval through that same validated transport. Robots redirects are canonicalized and followed manually with a hop/loop bound; authorization scope is retained; results are cached under the requesting origin and scope. Retrieval now uses per-origin single-flight locks, short failure caching, bounded exponential backoff with jitter, and retryable `408`/`429`/`5xx` decisions with `Retry-After` metadata.
- Made stored representations identity-aware with a canonical effective URL. Validators are sent only to that identity, never carried across redirects, and a `304` revalidates both the current byte limit and MIME before reuse.
- Made MIME normalization locale-independent and accepted registered structured `+xml` and `+json` types alongside the approved text/document types.
- Closed Chromium's direct egress by launching it against a dead local proxy and blocking service workers. Every allowed HTTP(S) document, script, stylesheet, XHR, and fetch route is fulfilled only from the validated Java transport; disallowed resource types are aborted. Per-response, request-count, aggregate-byte, final-status, final-MIME, and pre-JVM DOM byte bounds are enforced. DOM stabilization remains inside one hard maximum.

### Round 1 TDD Evidence

Initial focused redesign command:

```powershell
& 'C:\Users\moham\.m2\wrapper\dists\apache-maven-3.8.6-bin\1ks0nkde5v1pk9vtc31i9d0lcd\apache-maven-3.8.6\bin\mvn.cmd' test '-Dtest=ValidatedHttpTransportTest,RobotsPolicyServiceTest,CrawlFetcherTest' '-Dmaven.repo.local=C:\Users\moham\.m2\repository'
```

Result: expected `BUILD FAILURE` during test compilation. The wished-for `DestinationValidator`, `ValidatedHttpTransport`, effective representation identity, and structured robots-unavailable metadata did not yet exist.

The later throttling-focused RED compiled after the fetch API redesign but failed because `FetchResult.Rejected` did not yet carry `retryable` and `retryAfter`. After adding the minimum metadata propagation, that focused test passed: 1 test, 0 failures/errors/skips.

### Round 1 GREEN and Verification Evidence

Task 3 plus the relevant Task 2 safety suite:

```powershell
& 'C:\Users\moham\.m2\wrapper\dists\apache-maven-3.8.6-bin\1ks0nkde5v1pk9vtc31i9d0lcd\apache-maven-3.8.6\bin\mvn.cmd' test '-Dtest=ValidatedHttpTransportTest,RobotsPolicyServiceTest,CrawlFetcherTest,CrawlPolicyTest,UrlCanonicalizerTest' '-Dmaven.repo.local=C:\Users\moham\.m2\repository'
```

Result: `BUILD SUCCESS`; 52 tests run, 0 failures, 0 errors, 0 skipped.

Compile:

```powershell
& 'C:\Users\moham\.m2\wrapper\dists\apache-maven-3.8.6-bin\1ks0nkde5v1pk9vtc31i9d0lcd\apache-maven-3.8.6\bin\mvn.cmd' compile '-DskipTests' '-Dmaven.repo.local=C:\Users\moham\.m2\repository'
```

Result: `BUILD SUCCESS`; only the pre-existing deprecated `URL(String)` warning in legacy `ScrapeService.kt`.

Full backend regression suite:

```powershell
& 'C:\Users\moham\.m2\wrapper\dists\apache-maven-3.8.6-bin\1ks0nkde5v1pk9vtc31i9d0lcd\apache-maven-3.8.6\bin\mvn.cmd' test '-Dmaven.repo.local=C:\Users\moham\.m2\repository'
```

Result: `BUILD SUCCESS`; 97 tests run, 0 failures, 0 errors, 0 skipped. The existing Surefire no-fork, PDFBox cache-directory, and MockK/ByteBuddy dynamic-agent warnings remain non-failing.

### Round 1 Self-Review and Concerns

- Captured-handler tests cover dead-proxy Chromium launch, validated route fulfillment, robots rejection before subresource I/O, and DOM rejection before `page.content()` allocation. Transport tests prove the validated address is used even when the hostname cannot resolve and that the original Host header is preserved.
- Redirect-loop, robots redirect/cache, robots `429`, target `429`, cross-destination `304`, structured MIME suffix, cached-representation limit, and total-deadline behavior have focused coverage.
- No policy or canonicalization rule from Task 2 was duplicated or weakened; the small `DestinationValidator` seam exposes the existing decision and its resolved-address snapshot to both production transport and deterministic tests.
- The legacy `ScrapeService` wiring concern from the initial report remains deferred to the orchestration task. No additional blocking concern remains for Task 3.
