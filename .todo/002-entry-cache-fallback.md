# Local cache fallback for Entry API outage (stale-on-error)

Difficulty: Medium — recommend starting with an Opus-class model.

Depends on: 001 (fast-fail when the breaker is open is a premise of this design).

## Context / Why

When the upstream Entry API fails, the circuit breaker opens and every page that
reads `EntryClient` errors out. The top page entry list and each entry detail
should keep working from a local cache with TTL-based refresh: normal traffic
populates the cache, an expired item triggers a re-fetch, and when upstream is
unavailable (breaker open, timeout, 5xx) the cached value is served even if
stale.

Design decisions (agreed in session 2026-10-28):

- **No registry peeking.** The fallback is stale-on-error only: catch upstream
  failures at the client boundary, serve the cached value regardless of TTL.
  Short-circuiting while the breaker is open comes for free — open-state calls
  fail fast (todo 001) and never touch upstream, so the 1-minute
  `waitDurationInOpenState` → half-open → probe → closed recovery loop works
  naturally and re-warms the cache.
- **Decorator, not controller changes.** `CachingEntryClient` wraps the existing
  `EntryClient` as `@Component @Primary`; controllers stay untouched. Feeds,
  sitemap, llms, tag and category pages are protected by the same decorator
  because they all call `EntryClient` directly.
- **Package placement**: `am.ik.blog.entry` — feature packages must not depend on
  `am.ik.blog.config` (ArchUnit-enforced). `CircuitBreakerRegistry` is NOT
  injected anywhere in this design.

## Target files

- `am.ik.blog.entry.CachingEntryClient` (new) — decorator, `@Primary`
- `am.ik.blog.entry.EntryCache` (new) — Caffeine `Cache<Key, Cached<T>>`;
  `Key = record(tenantId, operation, canonicalQuery)` (list: normalized
  query/tag/categories/cursor/direction/size; detail: entryId),
  `Cached = record(T value, Instant fetchedAt)`
- `pom.xml` — add Caffeine (`maximumSize` ~1000 to bound list-key growth)
- `AppConfig` / a `@ConfigurationProperties` record (`blog.entry-cache.*`) for
  the TTLs below

## Per-operation behavior

| Operation | Success | Upstream failure (open / timeout / 5xx) |
|---|---|---|
| `findEntries` | cache, return | return cache even if expired; rethrow if none |
| `findById` | cache, return; **404 cached as negative entry (short TTL)** | same as above |
| `findMarkdownById` | cache, return | same as above |
| `findAllTags` / `findAllCategories` | cache, return | same as above |
| `headEntry` (conditional request) | — | swallow and report failure to `evaluateConditional` so it falls through to the full-render path; the full render's `findById` is what the cache rescues. No 304 synthesis from cached `updated.date`. |

4xx responses other than the handled 404s are not fallback triggers.

## Freshness

Lazy refresh on access: `fetchedAt + TTL < now` → re-fetch from upstream; on
success replace, on failure serve stale. TTLs in properties, aligned with the
client-facing cache headers:

- list: 1m (matches `LIST_CACHE_CONTROL`)
- detail: 1h (matches `ENTRY_CACHE_CONTROL`)
- negative (404): 5m
- tags / categories: 1h

## Observability

Micrometer counter `blog.entry.cache`, tags `op=list|detail|markdown|tags|categories`,
`result=fresh|stale|miss|negative`. Prometheus registry is already present.

## GraalVM notes

Values stay as in-heap POJOs already flowing through Jackson — no new
`BlogRuntimeHints` entries needed. The `blog.entry-cache.*` properties record is
binding-only (never rendered by Mustache), so `BINDING_TYPES` alone suffices.

## Implementation steps

1. Add Caffeine dependency.
2. Implement `EntryCache` + `CachingEntryClient` with the fallback table above.
3. Add the TTL properties record and wire it.
4. Tests (single new test class, MockServer at the HTTP layer — never mock
   `EntryClient`):
   - success response is cached (two upstream calls return the first body)
   - upstream switched to 5xx still serves an expired cache entry
   - failure with an empty cache propagates the existing error rendering
   - todo 001's fast-fail guard doubles as the outage latency check

## Acceptance criteria

- [ ] With the upstream down, `/` and `/entries/{id}` render from cache while
      stale data exists.
- [ ] With no cached value, the existing error behavior is unchanged.
- [ ] Recovery: after upstream returns, the next expired-TTL access serves
      fresh data.
- [ ] `./mvnw spring-javaformat:apply test` passes.

## Verification

- Local run: `./mvnw spring-boot:run`, then stop/unreachable the upstream and
  reload `/` — the previously rendered list should still appear.
