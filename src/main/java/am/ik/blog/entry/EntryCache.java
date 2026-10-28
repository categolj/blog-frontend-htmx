package am.ik.blog.entry;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Caffeine-backed local cache for Entry API responses, keyed by tenant + operation +
 * canonical query. Entries are plain in-heap POJOs already flowing through Jackson.
 *
 * <p>
 * Expiry is checked lazily on access against the injected {@link InstantSource} rather
 * than by Caffeine's own ticker: the caller distinguishes fresh from expired values so a
 * failed upstream re-fetch can still serve the expired entry (stale-on-error fallback).
 * Caffeine contributes the {@code maximumSize} bound against unbounded list-key growth.
 */
@Component
public class EntryCache {

	private final Cache<Key, Cached<?>> cache = Caffeine.newBuilder().maximumSize(1_000).build();

	private final InstantSource instantSource;

	private final EntryCacheProps props;

	public EntryCache(InstantSource instantSource, EntryCacheProps props) {
		this.instantSource = instantSource;
		this.props = props;
	}

	/**
	 * Returns the cached value when it is still within its TTL.
	 */
	public <T> Optional<Cached<T>> get(Key key) {
		return this.<T>lookup(key).filter(cached -> !cached.isExpired(this.instantSource.instant()));
	}

	/**
	 * Returns the cached value even when it is past its TTL — the stale value served when
	 * upstream is unavailable.
	 */
	public <T> Optional<Cached<T>> getStale(Key key) {
		return this.<T>lookup(key);
	}

	/**
	 * Caches the value under the key. Negative results (cached 404s) get the short
	 * negative TTL so a since-published entry becomes visible quickly.
	 */
	public <T> void put(Key key, T value, boolean negative) {
		Duration ttl = negative ? this.props.negative() : this.ttlFor(key.operation());
		this.cache.put(key, new Cached<>(value, this.instantSource.instant(), ttl));
	}

	/** Clears every cached entry. Visible to tests. */
	public void clear() {
		this.cache.invalidateAll();
	}

	private Duration ttlFor(String operation) {
		return switch (operation) {
			case "list" -> this.props.list();
			case "detail", "markdown" -> this.props.detail();
			default -> this.props.collections();
		};
	}

	@SuppressWarnings("unchecked")
	private <T> Optional<Cached<T>> lookup(Key key) {
		Cached<?> cached = this.cache.getIfPresent(key);
		return cached == null ? Optional.empty() : Optional.of((Cached<T>) cached);
	}

	/**
	 * Cache key combining the upstream tenant, the client operation, and a canonical
	 * rendering of the operation's arguments.
	 */
	public record Key(@Nullable String tenantId, String operation, String query) {
	}

	/**
	 * A cached value with the instant it was fetched and the TTL it was stored under.
	 */
	public record Cached<T>(T value, Instant fetchedAt, Duration ttl) {

		/** {@code fetchedAt + TTL < now} means the entry must be re-fetched. */
		public boolean isExpired(Instant now) {
			return this.fetchedAt.plus(this.ttl).isBefore(now);
		}
	}

}
