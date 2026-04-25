package am.ik.blog.entry.web;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Round-trips the {@code {categories}} path-variable value of
 * {@code /categories/{categories}/entries}: a comma-joined chain of category names that
 * matches the React frontend.
 *
 * <p>
 * Scope is intentionally limited to the single path segment so {@link #of} and
 * {@link #parse} stay symmetric. Callers wrap the produced segment into a full URL via
 * {@code UriComponentsBuilder.pathSegment(CategoryUrl.of(chain))} (which handles
 * percent-encoding for spaces etc.), and {@code @PathVariable} hands the decoded segment
 * straight to {@link #parse} on the way in.
 */
public final class CategoryUrl {

	private CategoryUrl() {
	}

	/**
	 * Joins {@code chain} into the comma-separated form ({@code "a,b,c"}). Empty input is
	 * rejected so callers can't synthesize a malformed path segment.
	 */
	public static String of(List<String> chain) {
		if (chain.isEmpty()) {
			throw new IllegalArgumentException("chain must have at least one segment");
		}
		return String.join(",", chain);
	}

	/**
	 * Splits the segment back into chain parts. Returns {@code null} for blank input or
	 * any empty component ({@code ",a"}, {@code "a,,b"}, {@code "a,"}).
	 */
	@Nullable public static List<String> parse(String segment) {
		if (segment.isBlank()) {
			return null;
		}
		String[] parts = segment.split(",", -1);
		List<String> chain = new ArrayList<>(parts.length);
		for (String part : parts) {
			String trimmed = part.trim();
			if (trimmed.isEmpty()) {
				return null;
			}
			chain.add(trimmed);
		}
		return chain;
	}

}
