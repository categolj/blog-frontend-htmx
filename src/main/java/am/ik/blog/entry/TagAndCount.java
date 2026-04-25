package am.ik.blog.entry;

import org.jspecify.annotations.Nullable;

/**
 * Mirrors the API's flattened representation where Tag fields are unwrapped.
 */
public record TagAndCount(String name, @Nullable String version, int count) {
}
