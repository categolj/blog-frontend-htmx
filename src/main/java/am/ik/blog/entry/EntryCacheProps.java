package am.ik.blog.entry;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * TTLs for the local entry cache served by {@link CachingEntryClient}. Defaults mirror
 * the client-facing {@code Cache-Control} headers: list pages revalidate every minute,
 * entry detail and markdown every hour, and cached 404s expire quickly so a
 * since-published entry shows up without waiting for a full TTL cycle.
 */
@ConfigurationProperties(prefix = "blog.entry-cache")
public record EntryCacheProps(@DefaultValue("1m") Duration list, @DefaultValue("1h") Duration detail,
		@DefaultValue("1h") Duration collections, @DefaultValue("5m") Duration negative) {
}
