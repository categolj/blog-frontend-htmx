package am.ik.blog.entry;

import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Caffeine-backed local store of the last successful Entry API response per tenant +
 * operation + canonical query. Entries are plain in-heap POJOs already flowing through
 * Jackson.
 *
 * <p>
 * There is no expiry: the store is never consulted while upstream answers, only when it
 * fails (see {@link CachingEntryClient}), and an outage can last for days. Caffeine
 * contributes the {@code maximumSize} bound against unbounded list-key growth.
 */
@Component
public class EntryCache {

	private final Cache<Key, Object> cache = Caffeine.newBuilder().maximumSize(1_000).build();

	/**
	 * Returns the last value stored under the key, however old.
	 */
	@SuppressWarnings("unchecked")
	public <T> Optional<T> get(Key key) {
		return Optional.ofNullable((T) this.cache.getIfPresent(key));
	}

	/**
	 * Stores a successful result under the key, replacing any previous one. Only values
	 * that describe an existing resource are stored — see {@link CachingEntryClient} for
	 * why not-found responses are kept out.
	 */
	public void put(Key key, Object value) {
		this.cache.put(key, value);
	}

	/** Clears every cached entry. Visible to tests. */
	public void clear() {
		this.cache.invalidateAll();
	}

	/**
	 * Cache key combining the upstream tenant, the client operation, and a canonical
	 * rendering of the operation's arguments.
	 */
	public record Key(@Nullable String tenantId, String operation, String query) {
	}

}
