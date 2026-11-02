package am.ik.blog.entry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import am.ik.blog.entry.EntryCache.Key;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Stale-on-error fallback in front of {@link EntryClient}: every call goes to upstream,
 * and each successful answer is kept as the last known good value. When upstream is
 * unavailable (open breaker fast-fail, timeout, 5xx) that value is served however old it
 * is; with nothing stored the upstream failure is rethrown. Other 4xx responses are data
 * errors, not outages — they propagate untouched.
 *
 * <p>
 * The store is deliberately not a TTL cache answering in place of a healthy upstream: an
 * edited entry would otherwise keep rendering its old body while
 * {@link #headEntry(Long, Instant, String)}, which always asks upstream, already reports
 * it modified.
 *
 * <p>
 * Only successful responses are cached. A not-found result is never stored: a 404 handed
 * back while upstream is degraded (a gateway answering for a down backend, for example)
 * says nothing about the resource, so storing it would overwrite the real entry the
 * fallback depends on. Upstream failures stay a circuit-breaker concern.
 *
 * <p>
 * All controllers and feed/sitemap/llms renderers depend on the {@link EntryClient} type;
 * declaring this subclass as {@code @Primary} routes every call through the cache without
 * touching any call site. The delegate's {@link RestClient} is handed to {@code super} so
 * the two beans share a single transport.
 */
@Component
@Primary
public class CachingEntryClient extends EntryClient {

	private static final String COUNTER_NAME = "blog.entry.cache";

	private final EntryCache cache;

	private final MeterRegistry meterRegistry;

	public CachingEntryClient(EntryClient delegate, EntryCache cache, MeterRegistry meterRegistry) {
		// The injected bean is the plain EntryClient, not this @Primary decorator: Spring
		// considers a bean's dependency on its own type (a self reference) only as a last
		// resort, so the parameter resolves to the plain client.
		super(delegate.restClient());
		this.cache = cache;
		this.meterRegistry = meterRegistry;
	}

	@Override
	public CursorPage<Entry> findEntries(EntryQuery query, @Nullable String tenantId) {
		Key key = new Key(tenantId, "list", canonicalListQuery(query));
		return this.readThrough(key, "list", () -> super.findEntries(query, tenantId), value -> true);
	}

	@Override
	public Optional<Entry> findById(Long entryId, @Nullable String tenantId) {
		Key key = new Key(tenantId, "detail", entryId.toString());
		return this.readThrough(key, "detail", () -> super.findById(entryId, tenantId), Optional::isPresent);
	}

	@Override
	public Optional<String> findMarkdownById(Long entryId, @Nullable String tenantId) {
		Key key = new Key(tenantId, "markdown", entryId.toString());
		return this.readThrough(key, "markdown", () -> super.findMarkdownById(entryId, tenantId), Optional::isPresent);
	}

	@Override
	public List<TagAndCount> findAllTags(@Nullable String tenantId) {
		Key key = new Key(tenantId, "tags", "");
		return this.readThrough(key, "tags", () -> super.findAllTags(tenantId), value -> true);
	}

	@Override
	public List<List<Category>> findAllCategories(@Nullable String tenantId) {
		Key key = new Key(tenantId, "categories", "");
		return this.readThrough(key, "categories", () -> super.findAllCategories(tenantId), value -> true);
	}

	/**
	 * Conditional requests are never served from the cache — synthesizing a 304 from
	 * stale metadata would freeze the page's Last-Modified/ETag negotiation. On upstream
	 * failure the call reports a non-304 status so the caller's conditional evaluation
	 * falls through to the full-render path, where the cached entry detail serves the
	 * page.
	 */
	@Override
	public ResponseEntity<Void> headEntry(Long entryId, Instant ifModifiedSince, @Nullable String tenantId) {
		try {
			return super.headEntry(entryId, ifModifiedSince, tenantId);
		}
		catch (ResourceAccessException | HttpServerErrorException e) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
		}
	}

	/**
	 * Loads the value from upstream and, when {@code cacheable} accepts it, stores it as
	 * the fallback. On an upstream failure the stored value (if any) is served instead.
	 */
	private <T> T readThrough(Key key, String op, Supplier<T> loader, Predicate<T> cacheable) {
		try {
			T value = loader.get();
			if (cacheable.test(value)) {
				this.cache.put(key, value);
				this.increment(op, "fresh");
			}
			else {
				// Not found: never stored.
				this.increment(op, "uncached");
			}
			return value;
		}
		catch (ResourceAccessException | HttpServerErrorException e) {
			// ResourceAccessException covers both real I/O failures and the open-breaker
			// CallNotPermittedException, which RestClient wraps as an I/O error.
			T stale = this.cache.<T>get(key).orElse(null);
			if (stale != null) {
				this.increment(op, "stale");
				return stale;
			}
			this.increment(op, "miss");
			throw e;
		}
	}

	private void increment(String op, String result) {
		this.meterRegistry.counter(COUNTER_NAME, "op", op, "result", result).increment();
	}

	/**
	 * Canonical rendering of the list arguments so equal queries share one cache key.
	 * Deliberately order-sensitive (cursor differs, categories are positional) to keep
	 * distinct pages distinct.
	 */
	private static String canonicalListQuery(EntryQuery query) {
		return String.join("|", StringUtils.hasText(query.query()) ? query.query() : "",
				StringUtils.hasText(query.tag()) ? query.tag() : "",
				query.categories() == null ? "" : String.join(",", query.categories()),
				StringUtils.hasText(query.cursor()) ? query.cursor() : "", query.direction().name(),
				String.valueOf(query.size()));
	}

}
