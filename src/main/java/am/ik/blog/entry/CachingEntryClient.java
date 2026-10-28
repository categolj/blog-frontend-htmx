package am.ik.blog.entry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import am.ik.blog.entry.EntryCache.Cached;
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
 * Read-through cache in front of {@link EntryClient} with a stale-on-error fallback: when
 * upstream is unavailable (open breaker fast-fail, timeout, 5xx) the cached value is
 * served even if past its TTL, and a missing cache entry rethrows the upstream failure.
 * Other 4xx responses are data errors, not outages — they propagate untouched.
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
		return this.readThrough(key, "list", () -> super.findEntries(query, tenantId), value -> false);
	}

	@Override
	public Optional<Entry> findById(Long entryId, @Nullable String tenantId) {
		Key key = new Key(tenantId, "detail", entryId.toString());
		return this.readThrough(key, "detail", () -> super.findById(entryId, tenantId), Optional::isEmpty);
	}

	@Override
	public Optional<String> findMarkdownById(Long entryId, @Nullable String tenantId) {
		Key key = new Key(tenantId, "markdown", entryId.toString());
		return this.readThrough(key, "markdown", () -> super.findMarkdownById(entryId, tenantId), Optional::isEmpty);
	}

	@Override
	public List<TagAndCount> findAllTags(@Nullable String tenantId) {
		Key key = new Key(tenantId, "tags", "");
		return this.readThrough(key, "tags", () -> super.findAllTags(tenantId), value -> false);
	}

	@Override
	public List<List<Category>> findAllCategories(@Nullable String tenantId) {
		Key key = new Key(tenantId, "categories", "");
		return this.readThrough(key, "categories", () -> super.findAllCategories(tenantId), value -> false);
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
	 * Cache hit within the TTL returns immediately; otherwise the value is loaded and
	 * stored. On an upstream failure the expired entry (if any) is served stale.
	 */
	private <T> T readThrough(Key key, String op, Supplier<T> loader, Predicate<T> negative) {
		Cached<T> fresh = this.cache.<T>get(key).orElse(null);
		if (fresh != null) {
			this.increment(op, negative.test(fresh.value()) ? "negative" : "fresh");
			return fresh.value();
		}
		try {
			T value = loader.get();
			boolean negativeResult = negative.test(value);
			this.cache.put(key, value, negativeResult);
			this.increment(op, negativeResult ? "negative" : "fresh");
			return value;
		}
		catch (ResourceAccessException | HttpServerErrorException e) {
			// ResourceAccessException covers both real I/O failures and the open-breaker
			// CallNotPermittedException, which RestClient wraps as an I/O error.
			Cached<T> stale = this.cache.<T>getStale(key).orElse(null);
			if (stale != null) {
				this.increment(op, "stale");
				return stale.value();
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
